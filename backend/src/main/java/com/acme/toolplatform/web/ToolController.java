package com.acme.toolplatform.web;

import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.service.ToolRegistryService;
import com.acme.toolplatform.web.dto.CreateToolRequest;
import com.acme.toolplatform.web.dto.PageResponse;
import com.acme.toolplatform.web.dto.ToolResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API is versioned in the path (/api/v1/...).
 *
 * Two different kinds of "version" live in this system and interviewers love
 * the distinction:
 *   - /api/v1  = the version of THIS HTTP CONTRACT
 *   - 1.2      = the version of the DISTRIBUTED ARTIFACT
 * They evolve independently.
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolController {

    private final ToolRegistryService registry;

    public ToolController(ToolRegistryService registry) {
        this.registry = registry;
    }

    /** 201 Created + Location header pointing at the new resource. */
    @PostMapping
    public ResponseEntity<ToolResponse> createTool(@Valid @RequestBody CreateToolRequest request) {
        Tool tool = registry.registerTool(request.name(), request.description());
        return ResponseEntity
                .created(URI.create("/api/v1/tools/" + tool.getName()))
                .body(ToolResponse.from(tool));
    }

    @GetMapping
    public PageResponse<ToolResponse> listTools(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.clamp(size, 1, 100); // never let a client ask for the whole table
        return PageResponse.of(
                registry.listTools(PageRequest.of(Math.max(page, 0), safeSize, Sort.by("name"))),
                ToolResponse::from);
    }

    @GetMapping("/{toolName}")
    public ToolResponse getTool(@PathVariable String toolName) {
        return ToolResponse.from(registry.getTool(toolName));
    }
}
