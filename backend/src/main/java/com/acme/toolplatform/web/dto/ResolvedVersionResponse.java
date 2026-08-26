package com.acme.toolplatform.web.dto;

import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionSelector;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.service.VersionResolution;

/**
 * What a client gets, and why it got it.
 *
 * {@code selector} is included on purpose: "2.0 because you are pinned to it"
 * and "2.0 because you follow latest" look identical without it, and they are
 * completely different facts when you are debugging an incident.
 */
public record ResolvedVersionResponse(
        String client,
        String tool,
        VersionSelector selector,
        String resolvedVersion,
        String artifactPath,
        String checksumSha256,
        VersionStatus status,
        boolean deprecated) {

    public static ResolvedVersionResponse from(String clientName, String toolName, VersionResolution r) {
        ToolVersion v = r.version();
        return new ResolvedVersionResponse(
                clientName,
                toolName,
                r.selector(),
                v.getVersion(),
                v.getArtifactPath(),
                v.getChecksumSha256(),
                v.getStatus(),
                r.isDeprecated());
    }
}
