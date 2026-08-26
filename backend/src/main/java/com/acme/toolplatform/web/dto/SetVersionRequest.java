package com.acme.toolplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Either a concrete version ("1.2") or the literal "latest".
 *
 * "latest" is accepted ONLY here, at configuration time, where it is a
 * deliberate, audited decision by a named client. It is still rejected by the
 * registry's exact-lookup endpoint, where it would just be a bug.
 */
public record SetVersionRequest(

        @NotBlank(message = "version is required")
        @Pattern(
            regexp = "^(?i)(latest|\\d{1,6}\\.\\d{1,6}(\\.\\d{1,6})?)$",
            message = "version must be MAJOR.MINOR[.PATCH] (e.g. 1.2) or the literal 'latest'")
        String version) {
}
