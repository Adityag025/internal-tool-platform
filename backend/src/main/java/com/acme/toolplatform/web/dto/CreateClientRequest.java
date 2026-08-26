package com.acme.toolplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateClientRequest(

        @NotBlank(message = "name is required")
        @Pattern(
            regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
            message = "name must be a lowercase slug, e.g. 'client-a'")
        String name,

        @Size(max = 512)
        String description) {
}
