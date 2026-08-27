package com.acme.toolplatform.artifact;

/**
 * The PORT: everything the platform needs from "somewhere that holds bytes".
 *
 * The registry deals in coordinates and metadata; this interface is the only
 * place that knows bytes exist. Two adapters implement it - a local filesystem
 * one for development and CI, and a JFrog Artifactory one for the real thing -
 * and no business code changes when you swap them.
 *
 * This is not abstraction for its own sake. It buys three concrete things:
 * integration tests that need no Artifactory, a CI pipeline that does not
 * depend on an external service being up, and the freedom to move to S3 or a
 * different repository manager without touching the service layer.
 */
public interface ArtifactStore {

    /**
     * Store bytes at {@code path}. Implementations MUST refuse to overwrite an
     * existing path - artifacts are immutable.
     *
     * @return the coordinates and the SHA-256 the store actually holds
     */
    StoredArtifact store(String path, byte[] content);

    /** @throws com.acme.toolplatform.service.exception.ArtifactMissingException if absent */
    ArtifactContent retrieve(String path);

    boolean exists(String path);

    /** Human-readable identity for logs and the health endpoint. */
    String describe();
}
