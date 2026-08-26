package com.acme.toolplatform.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Input is validated at the edge. A tool name becomes part of a repository
 * path (data-validator/1.2/...), so it is restricted to a safe slug -
 * this also blocks path-traversal attempts like "../../etc".
 */
public record CreateToolRequest(

        @NotBlank(message = "name is required")
        @Pattern(
            regexp = "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$",
            message = "name must be a lowercase slug, e.g. 'data-validator'")
        String name,

        @Size(max = 512, message = "description must be at most 512 characters")
        String description) {
}
