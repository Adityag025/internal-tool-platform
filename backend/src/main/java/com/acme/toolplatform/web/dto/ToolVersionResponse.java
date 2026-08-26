package com.acme.toolplatform.web.dto;

import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionStatus;
import java.time.Instant;

/**
 * Note that the tool NAME is passed in rather than read from
 * {@code version.getTool().getName()}.
 *
 * The tool association is LAZY and {@code spring.jpa.open-in-view} is false,
 * so by the time the controller maps the entity the persistence session is
 * already closed - touching the proxy here would throw
 * LazyInitializationException. The caller always knows the tool name (it is
 * in the URL), so we simply pass it and never touch the proxy.
 */
public record ToolVersionResponse(
        Long id,
        String tool,
        String version,
        String artifactPath,
        String checksumSha256,
        VersionStatus status,
        Instant createdAt) {

    public static ToolVersionResponse from(String toolName, ToolVersion v) {
        return new ToolVersionResponse(
                v.getId(),
                toolName,
                v.getVersion(),
                v.getArtifactPath(),
                v.getChecksumSha256(),
                v.getStatus(),
                v.getCreatedAt());
    }
}
