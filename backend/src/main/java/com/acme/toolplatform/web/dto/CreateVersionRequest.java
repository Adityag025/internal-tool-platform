package com.acme.toolplatform.web.dto;

import com.acme.toolplatform.domain.VersionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateVersionRequest(

        @NotBlank(message = "version is required")
        @Pattern(
            regexp = "^\\d{1,6}\\.\\d{1,6}(\\.\\d{1,6})?$",
            message = "version must be MAJOR.MINOR[.PATCH], e.g. 1.2 or 1.2.3")
        String version,

        @NotBlank(message = "artifactPath is required")
        @Size(max = 512)
        String artifactPath,

        @Pattern(
            regexp = "^[a-fA-F0-9]{64}$",
            message = "checksumSha256 must be 64 hex characters")
        String checksumSha256,

        /** Optional; defaults to PUBLISHED. */
        VersionStatus status) {
}
