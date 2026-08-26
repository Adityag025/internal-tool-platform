package com.acme.toolplatform.web.dto;

import com.acme.toolplatform.domain.Tool;
import java.time.Instant;

public record ToolResponse(Long id, String name, String description, Instant createdAt) {

    public static ToolResponse from(Tool tool) {
        return new ToolResponse(tool.getId(), tool.getName(), tool.getDescription(), tool.getCreatedAt());
    }
}
