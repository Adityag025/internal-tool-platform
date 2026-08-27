package com.acme.toolplatform.service;

import com.acme.toolplatform.artifact.ArtifactContent;
import com.acme.toolplatform.artifact.ArtifactStore;
import com.acme.toolplatform.artifact.ArtifactStoreProperties;
import com.acme.toolplatform.artifact.StoredArtifact;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.repository.ToolVersionRepository;
import com.acme.toolplatform.service.exception.ChecksumMismatchException;
import com.acme.toolplatform.service.exception.DuplicateResourceException;
import com.acme.toolplatform.service.exception.VersionRevokedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves actual bytes in and out, and is the only place that decides whether
 * bytes may be trusted.
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ArtifactStore store;
    private final ToolRegistryService registry;
    private final ClientConfigurationService clients;
    private final ToolVersionRepository versionRepository;
    private final ArtifactStoreProperties properties;

    public ArtifactService(ArtifactStore store,
                           ToolRegistryService registry,
                           ClientConfigurationService clients,
                           ToolVersionRepository versionRepository,
                           ArtifactStoreProperties properties) {
        this.store = store;
        this.registry = registry;
        this.clients = clients;
        this.versionRepository = versionRepository;
        this.properties = properties;
    }

    /**
     * Upload the bytes for an already-registered version.
     *
     * Called by CI after a successful build. Rejected if the version already
     * has bytes: a published version's content is fixed forever. Wanting to
     * change it means wanting a new version.
     */
    @Transactional
    public ToolVersion uploadArtifact(String toolName, String rawVersion, byte[] content) {
        ToolVersion version = registry.resolveExactVersion(toolName, rawVersion);

        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("Refusing to store an empty artifact");
        }
        if (content.length > properties.getMaxArtifactBytes()) {
            throw new IllegalArgumentException(
                    "Artifact is " + content.length + " bytes, limit is " + properties.getMaxArtifactBytes());
        }
        if (version.getStatus() == VersionStatus.REVOKED) {
            throw new VersionRevokedException(
                    "Version '" + rawVersion + "' of '" + toolName + "' is REVOKED; it cannot receive bytes");
        }
        if (version.hasArtifact()) {
            throw new DuplicateResourceException(
                    "Version '" + rawVersion + "' of '" + toolName + "' already has an artifact "
                            + "(sha256=" + version.getChecksumSha256() + ") and is immutable; publish a new version");
        }

        StoredArtifact stored = store.store(version.getArtifactPath(), content);
        version.sealWith(stored.sha256());
        versionRepository.save(version);

        log.info("artifact.uploaded tool={} version={} path={} bytes={} sha256={}",
                toolName, version.getVersion(), stored.path(), stored.sizeBytes(), stored.sha256());
        return version;
    }

    /** Download by exact coordinates. */
    @Transactional(readOnly = true)
    public ArtifactDownload download(String toolName, String rawVersion) {
        ToolVersion version = registry.resolveExactVersion(toolName, rawVersion);
        return fetchAndVerify(toolName, version);
    }

    /**
     * Download whatever THIS client is configured to receive.
     *
     * This is the complete dynamic-version-loading path, end to end:
     * client -> configuration -> exact version -> coordinates -> bytes.
     */
    @Transactional(readOnly = true)
    public ArtifactDownload downloadForClient(String clientName, String toolName) {
        VersionResolution resolution = clients.resolveForClient(clientName, toolName);
        ArtifactDownload download = fetchAndVerify(toolName, resolution.version());
        log.info("artifact.delivered client={} tool={} selector={} version={} sha256={}",
                clientName, toolName, resolution.selector(),
                download.version().getVersion(), download.content().sha256());
        return download;
    }

    private ArtifactDownload fetchAndVerify(String toolName, ToolVersion version) {
        long startNanos = System.nanoTime();

        if (version.getStatus() == VersionStatus.REVOKED) {
            throw new VersionRevokedException(
                    "Version '" + version.getVersion() + "' of '" + toolName
                            + "' has been REVOKED and must not be downloaded");
        }

        ArtifactContent content = store.retrieve(version.getArtifactPath());

        // THE integrity check. The registry recorded a hash at publish time;
        // the store just handed us bytes. If they disagree, something was
        // corrupted or tampered with, and serving them anyway would defeat
        // the entire point of recording a checksum.
        if (version.hasArtifact() && !version.getChecksumSha256().equalsIgnoreCase(content.sha256())) {
            throw new ChecksumMismatchException(
                    "Checksum mismatch for " + version.getArtifactPath()
                            + ": registry recorded " + version.getChecksumSha256()
                            + " but the store returned " + content.sha256());
        }

        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("artifact.downloaded tool={} version={} path={} bytes={} verified={} latencyMs={}",
                toolName, version.getVersion(), version.getArtifactPath(),
                content.sizeBytes(), version.hasArtifact(), millis);

        return new ArtifactDownload(toolName, version, content);
    }
}
