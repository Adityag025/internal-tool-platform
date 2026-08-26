package com.acme.toolplatform.domain;

/**
 * How a client chooses which version of a tool it gets.
 *
 * There is no third "default" option on purpose. Every client has made an
 * explicit, recorded decision - including the decision to float.
 */
public enum VersionSelector {
    /** Always this exact version. The safe default for production consumers. */
    PINNED,
    /**
     * Deliberately opted in to the newest published version.
     *
     * Even here the resolution result is a CONCRETE version that gets logged,
     * so "which bytes actually ran?" is always answerable after the fact.
     */
    LATEST
}
