package com.acme.toolplatform.service;

import com.acme.toolplatform.artifact.ArtifactContent;
import com.acme.toolplatform.domain.ToolVersion;

/** An artifact plus the registry metadata a caller needs to trust it. */
public record ArtifactDownload(String toolName, ToolVersion version, ArtifactContent content) {

    /** Last path segment, e.g. data-validator-1.2.jar */
    public String filename() {
        String path = version.getArtifactPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
