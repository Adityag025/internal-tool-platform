package com.acme.toolplatform.artifact;

public record ArtifactContent(String path, String sha256, long sizeBytes, byte[] bytes) {

    /**
     * Bytes are buffered rather than streamed.
     *
     * That is a deliberate simplification for a learning project: buffering
     * lets us compute and verify the SHA-256 in one place, and internal tools
     * are megabytes, not gigabytes. A production system distributing large
     * artifacts would stream and digest incrementally, and would usually
     * redirect clients to a pre-signed URL rather than proxying the bytes
     * through this service at all.
     */
    public ArtifactContent {
    }
}
