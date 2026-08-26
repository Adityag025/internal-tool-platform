package com.acme.toolplatform.web;

import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.service.ToolRegistryService;
import com.acme.toolplatform.web.dto.CreateVersionRequest;
import com.acme.toolplatform.web.dto.ToolVersionResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tools/{toolName}/versions")
public class ToolVersionController {

    private final ToolRegistryService registry;

    public ToolVersionController(ToolRegistryService registry) {
        this.registry = registry;
    }

    /**
     * Called by the CI pipeline after a successful build.
     * Returns 409 if the version already exists - versions are immutable.
     */
    @PostMapping
    public ResponseEntity<ToolVersionResponse> publishVersion(
            @PathVariable String toolName,
            @Valid @RequestBody CreateVersionRequest request) {

        ToolVersion version = registry.publishVersion(
                toolName, request.version(), request.artifactPath(),
                request.checksumSha256(), request.status());

        return ResponseEntity
                .created(URI.create("/api/v1/tools/" + toolName + "/versions/" + version.getVersion()))
                .body(ToolVersionResponse.from(toolName, version));
    }

    @GetMapping
    public List<ToolVersionResponse> listVersions(@PathVariable String toolName) {
        return registry.listVersions(toolName).stream()
                .map(v -> ToolVersionResponse.from(toolName, v))
                .toList();
    }

    /**
     * Exact-version resolution.
     *
     * "1.2" arrives intact because Spring Boot 3 uses PathPatternParser, which
     * no longer strips anything after a dot the way the old AntPathMatcher did.
     */
    @GetMapping("/{version}")
    public ToolVersionResponse getVersion(@PathVariable String toolName, @PathVariable String version) {
        return ToolVersionResponse.from(toolName, registry.resolveExactVersion(toolName, version));
    }
}
