package com.acme.toolplatform.web.dto;

import com.acme.toolplatform.domain.ClientToolConfiguration;
import com.acme.toolplatform.domain.VersionSelector;
import java.time.Instant;

public record ClientConfigurationResponse(
        String tool,
        VersionSelector selector,
        /** null when the client follows LATEST. */
        String pinnedVersion,
        Instant updatedAt) {

    /** Safe only when the tool association has been fetch-joined. */
    public static ClientConfigurationResponse from(ClientToolConfiguration c) {
        return from(c.getTool().getName(), c);
    }

    public static ClientConfigurationResponse from(String toolName, ClientToolConfiguration c) {
        return new ClientConfigurationResponse(
                toolName,
                c.getSelector(),
                c.getPinnedVersion() == null ? null : c.getPinnedVersion().getVersion(),
                c.getUpdatedAt());
    }
}
