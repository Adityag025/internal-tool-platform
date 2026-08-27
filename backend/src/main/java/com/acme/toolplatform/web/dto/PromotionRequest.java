package com.acme.toolplatform.web.dto;

import com.acme.toolplatform.domain.VersionStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PromotionRequest(

        @NotNull(message = "status is required (DRAFT, PUBLISHED, DEPRECATED, REVOKED)")
        VersionStatus status,

        /** Free text for the audit trail, e.g. "CVE-2026-1234". */
        @Size(max = 512)
        String reason) {
}
