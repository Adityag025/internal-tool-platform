package com.acme.toolplatform.domain;

/**
 * Lifecycle of a published artifact version.
 *
 * The bytes of a version are IMMUTABLE once published; only this status may
 * change. That is what "artifact promotion" means in release engineering:
 * you do not rebuild to move a candidate forward, you re-label the exact
 * bytes that were already tested.
 */
public enum VersionStatus {
    /** Uploaded, not yet cleared for consumers. */
    DRAFT,
    /** Cleared for general consumption. */
    PUBLISHED,
    /** Still downloadable, but consumers should migrate off it. */
    DEPRECATED,
    /** Pulled (security issue / bad build). Downloads must fail loudly. */
    REVOKED
}
