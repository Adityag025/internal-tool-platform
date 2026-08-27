package com.acme.toolplatform.artifact;

public record StoredArtifact(String path, String sha256, long sizeBytes) {
}
