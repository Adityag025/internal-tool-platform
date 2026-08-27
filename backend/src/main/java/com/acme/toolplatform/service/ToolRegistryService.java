package com.acme.toolplatform.service;

import com.acme.toolplatform.domain.SemanticVersion;
import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.repository.ToolRepository;
import com.acme.toolplatform.repository.ToolVersionRepository;
import com.acme.toolplatform.service.exception.DuplicateResourceException;
import com.acme.toolplatform.service.exception.IllegalPromotionException;
import com.acme.toolplatform.service.exception.InvalidVersionException;
import com.acme.toolplatform.service.exception.ResourceNotFoundException;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * All registry business rules live here, NOT in the controller.
 *
 * The controller's job is HTTP (status codes, JSON shapes). This class owns
 * the rules that must hold no matter who calls them - the REST API today,
 * a CLI or a message consumer tomorrow.
 */
@Service
public class ToolRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistryService.class);

    /**
     * The promotion state machine.
     *
     * Artifact promotion means moving the SAME BYTES through a lifecycle -
     * never rebuilding to move forward. A rebuild would produce different
     * bytes, so "the thing we tested" and "the thing we released" would no
     * longer be the same artifact, and every test result before the promotion
     * would be worthless.
     *
     * REVOKED is terminal on purpose: un-revoking would mean a consumer who
     * correctly stopped using an artifact could be silently handed it again.
     */
    private static final Map<VersionStatus, Set<VersionStatus>> LEGAL_TRANSITIONS =
            new EnumMap<>(Map.of(
                    VersionStatus.DRAFT,      EnumSet.of(VersionStatus.PUBLISHED, VersionStatus.REVOKED),
                    VersionStatus.PUBLISHED,  EnumSet.of(VersionStatus.DEPRECATED, VersionStatus.REVOKED),
                    VersionStatus.DEPRECATED, EnumSet.of(VersionStatus.PUBLISHED, VersionStatus.REVOKED),
                    VersionStatus.REVOKED,    EnumSet.noneOf(VersionStatus.class)));

    private final ToolRepository toolRepository;
    private final ToolVersionRepository versionRepository;

    public ToolRegistryService(ToolRepository toolRepository, ToolVersionRepository versionRepository) {
        this.toolRepository = toolRepository;
        this.versionRepository = versionRepository;
    }

    // ------------------------------------------------------------------ tools

    @Transactional
    public Tool registerTool(String name, String description) {
        if (toolRepository.existsByName(name)) {
            throw new DuplicateResourceException("Tool '" + name + "' is already registered");
        }
        Tool saved = toolRepository.save(new Tool(name, description));
        log.info("tool.registered name={} id={}", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Tool> listTools(Pageable pageable) {
        return toolRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Tool getTool(String toolName) {
        return toolRepository.findByName(toolName)
                .orElseThrow(() -> new ResourceNotFoundException("Tool '" + toolName + "' is not registered"));
    }

    // --------------------------------------------------------------- versions

    /**
     * Publish a new immutable version of a tool.
     *
     * Rejects (409) if the version already exists. This is the single most
     * important rule in the platform: a published version is a permanent,
     * reproducible fact. If v1.2 could be overwritten, then "we tested v1.2"
     * would mean nothing.
     */
    @Transactional
    public ToolVersion publishVersion(String toolName, String rawVersion, String artifactPath,
                                      String checksumSha256, VersionStatus status) {
        Tool tool = getTool(toolName);

        SemanticVersion semver;
        try {
            semver = SemanticVersion.parse(rawVersion);
        } catch (IllegalArgumentException e) {
            throw new InvalidVersionException(e.getMessage());
        }

        if (versionRepository.existsByToolNameAndVersion(toolName, semver.raw())) {
            throw new DuplicateResourceException(
                    "Version '" + semver.raw() + "' of tool '" + toolName
                            + "' already exists and is immutable; publish a new version instead");
        }

        ToolVersion version = new ToolVersion(
                tool, semver, artifactPath, checksumSha256,
                status == null ? VersionStatus.PUBLISHED : status);

        try {
            ToolVersion saved = versionRepository.saveAndFlush(version);
            log.info("version.published tool={} version={} path={} status={}",
                    toolName, saved.getVersion(), saved.getArtifactPath(), saved.getStatus());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent publish of the same coordinates.
            // The DB unique constraint is the real guard; translate it to 409.
            throw new DuplicateResourceException(
                    "Version '" + semver.raw() + "' of tool '" + toolName + "' already exists (concurrent publish)");
        }
    }

    @Transactional(readOnly = true)
    public List<ToolVersion> listVersions(String toolName) {
        getTool(toolName); // 404 for an unknown tool instead of an empty list
        return versionRepository.findByToolNameOrderByMajorPartDescMinorPartDescPatchPartDesc(toolName);
    }

    /**
     * EXACT version resolution. Never falls back to "latest".
     *
     * A silent fallback would turn a typo ("1.20" instead of "1.2") into a
     * successful download of the wrong bytes - the worst possible failure mode
     * for a distribution system. Fail loudly with 404 instead.
     */
    @Transactional(readOnly = true)
    public ToolVersion resolveExactVersion(String toolName, String rawVersion) {
        long startNanos = System.nanoTime();
        getTool(toolName);

        if (!SemanticVersion.isValid(rawVersion)) {
            throw new InvalidVersionException(
                    "Version '" + rawVersion + "' is malformed; expected MAJOR.MINOR[.PATCH]");
        }

        ToolVersion found = versionRepository.findByToolNameAndVersion(toolName, rawVersion.trim())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Version '" + rawVersion + "' of tool '" + toolName + "' does not exist"));

        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        log.info("version.resolved tool={} version={} status={} latencyMs={}",
                toolName, found.getVersion(), found.getStatus(), millis);
        return found;
    }

    /**
     * Promote a version through its lifecycle without touching its bytes.
     *
     * DRAFT -> PUBLISHED is the release gate: it refuses to publish a version
     * that has no artifact, so "PUBLISHED" can never mean "a row exists but
     * there is nothing to download".
     */
    @Transactional
    public ToolVersion promote(String toolName, String rawVersion, VersionStatus target) {
        ToolVersion version = resolveExactVersion(toolName, rawVersion);
        VersionStatus current = version.getStatus();

        if (current == target) {
            return version; // idempotent: re-running a pipeline step is not an error
        }
        if (!LEGAL_TRANSITIONS.getOrDefault(current, Set.of()).contains(target)) {
            throw new IllegalPromotionException(
                    "Cannot promote '" + toolName + "' " + rawVersion + " from " + current + " to " + target
                            + "; allowed from " + current + ": " + LEGAL_TRANSITIONS.getOrDefault(current, Set.of()));
        }
        if (target == VersionStatus.PUBLISHED && !version.hasArtifact()) {
            throw new IllegalPromotionException(
                    "Cannot publish '" + toolName + "' " + rawVersion + ": no artifact has been uploaded yet");
        }

        version.promoteTo(target);
        versionRepository.save(version);
        log.info("version.promoted tool={} version={} from={} to={}", toolName, rawVersion, current, target);
        return version;
    }

    /**
     * The newest PUBLISHED version. Only used where a client has EXPLICITLY
     * opted into floating versions.
     *
     * DRAFT, DEPRECATED and REVOKED versions are skipped deliberately. A
     * client that opted in to "latest" asked to follow releases - it did not
     * ask to be moved onto a build that was never released, one you are being
     * asked to migrate off, or one that was withdrawn for a CVE. Without this
     * filter, revoking the newest version would break every floating consumer
     * instead of protecting them.
     */
    @Transactional(readOnly = true)
    public Optional<ToolVersion> findLatestVersion(String toolName) {
        return listVersions(toolName).stream()
                .filter(v -> v.getStatus() == VersionStatus.PUBLISHED)
                .findFirst();
    }
}
