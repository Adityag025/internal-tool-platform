package com.acme.toolplatform.artifact;

import com.acme.toolplatform.service.exception.ArtifactMissingException;
import com.acme.toolplatform.service.exception.ArtifactStoreException;
import java.net.URI;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Adapter: JFrog Artifactory over its REST API.
 *
 * Three calls are all this needs:
 *   PUT  /artifactory/{repo}/{path}              deploy
 *   GET  /artifactory/{repo}/{path}              download
 *   GET  /artifactory/api/storage/{repo}/{path}  metadata (checksums, size)
 *
 * "Artifact coordinates" is just the addressing scheme: repository + path,
 * where the path encodes name and version - data-validator/1.2/data-validator-1.2.jar.
 * Maven's groupId:artifactId:version and Docker's registry/repo:tag are the
 * same idea with different syntax: a globally unique address for one exact build.
 */
public class ArtifactoryArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(ArtifactoryArtifactStore.class);

    private final RestClient client;
    private final String repository;
    private final String baseUrl;

    public ArtifactoryArtifactStore(RestClient client, String baseUrl, String repository) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.repository = repository;
    }

    @Override
    public StoredArtifact store(String path, byte[] content) {
        String sha256 = Checksums.sha256(content);

        if (exists(path)) {
            throw new ArtifactStoreException("Artifact already exists and is immutable: " + path);
        }

        try {
            client.put()
                    .uri(artifactUri(path))
                    // Artifactory recomputes the digest and rejects the upload if
                    // it disagrees. Integrity is checked at the moment of transfer,
                    // not discovered later by a consumer.
                    .header("X-Checksum-Sha256", sha256)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(content)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ArtifactStoreException(
                                "Artifactory rejected the deploy of " + path + ": HTTP " + res.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (ArtifactStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ArtifactStoreException("Artifactory deploy failed for " + path, e);
        }

        log.info("artifact.stored store=artifactory repo={} path={} bytes={} sha256={}",
                repository, path, content.length, sha256);
        return new StoredArtifact(path, sha256, content.length);
    }

    @Override
    public ArtifactContent retrieve(String path) {
        byte[] bytes;
        try {
            bytes = client.get()
                    .uri(artifactUri(path))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new ArtifactMissingException("Artifact not present in Artifactory: " + path);
                    })
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new ArtifactStoreException(
                                "Artifactory download failed for " + path + ": HTTP " + res.getStatusCode());
                    })
                    .body(byte[].class);
        } catch (ArtifactMissingException | ArtifactStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new ArtifactStoreException("Artifactory download failed for " + path, e);
        }

        if (bytes == null) {
            throw new ArtifactMissingException("Artifactory returned an empty body for " + path);
        }
        return new ArtifactContent(path, Checksums.sha256(bytes), bytes.length, bytes);
    }

    @Override
    public boolean exists(String path) {
        try {
            client.get()
                    .uri(metadataUri(path))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (req, res) -> {
                        throw new ArtifactMissingException(path);
                    })
                    .toBodilessEntity();
            return true;
        } catch (ArtifactMissingException e) {
            return false;
        } catch (Exception e) {
            throw new ArtifactStoreException("Artifactory metadata lookup failed for " + path, e);
        }
    }

    @Override
    public String describe() {
        return "artifactory:" + baseUrl + "/" + repository;
    }

    /**
     * Build the artifact URI WITHOUT collapsing the path.
     *
     * The obvious version is wrong:
     *
     *     .uri("/artifactory/{repo}/{path}", repository, path)
     *
     * A URI template variable is a single path SEGMENT, so the slashes inside
     * "data-validator/1.2/data-validator-1.2.jar" get percent-encoded to %2F.
     * Artifactory then sees one flat filename instead of a nested path, and
     * the whole repository layout collapses into the repo root.
     *
     * Splitting into segments encodes each segment individually while keeping
     * the separators, which is what we actually want.
     */
    private Function<UriBuilder, URI> artifactUri(String path) {
        return builder -> builder
                .pathSegment("artifactory", repository)
                .pathSegment(path.split("/"))
                .build();
    }

    private Function<UriBuilder, URI> metadataUri(String path) {
        return builder -> builder
                .pathSegment("artifactory", "api", "storage", repository)
                .pathSegment(path.split("/"))
                .build();
    }
}
