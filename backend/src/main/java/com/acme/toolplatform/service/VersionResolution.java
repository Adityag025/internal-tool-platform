package com.acme.toolplatform.service;

import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionSelector;
import com.acme.toolplatform.domain.VersionStatus;

/**
 * The outcome of resolving "what does this client get?".
 *
 * It carries HOW the version was chosen alongside WHICH version won, because
 * a consumer needs both: "2.0, because you are pinned to it" and "2.0, because
 * you follow latest" are very different facts when something breaks.
 */
public record VersionResolution(VersionSelector selector, ToolVersion version) {

    public boolean isDeprecated() {
        return version.getStatus() == VersionStatus.DEPRECATED;
    }
}
