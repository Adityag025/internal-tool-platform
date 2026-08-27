package com.acme.toolplatform.web;

import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.service.ArtifactDownload;
import com.acme.toolplatform.service.ArtifactService;
import com.acme.toolplatform.service.ToolRegistryService;
import com.acme.toolplatform.web.dto.PromotionRequest;
import com.acme.toolplatform.web.dto.ToolVersionResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tools/{toolName}/versions/{version}")
public class ArtifactController {

    private final ArtifactService artifacts;
    private final ToolRegistryService registry;

    public ArtifactController(ArtifactService artifacts, ToolRegistryService registry) {
        this.artifacts = artifacts;
        this.registry = registry;
    }

    /**
     * Upload the bytes for a version. Called by CI after a successful build.
     *
     * Raw {@code application/octet-stream} rather than multipart, because that
     * is how artifact tooling actually works:
     *   curl -T target/data-validator-1.2.jar .../versions/1.2/artifact
     *
     * PUT because the content of these coordinates is being set to exactly
     * this. A second attempt gets 409, not a duplicate - immutability.
     */
    @PutMapping(path = "/artifact", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ToolVersionResponse> uploadArtifact(
            @PathVariable String toolName,
            @PathVariable String version,
            @RequestBody byte[] content) {

        ToolVersion saved = artifacts.uploadArtifact(toolName, version, content);
        return ResponseEntity
                .created(java.net.URI.create(
                        "/api/v1/tools/" + toolName + "/versions/" + version + "/artifact"))
                .eTag("\"" + saved.getChecksumSha256() + "\"")
                .body(ToolVersionResponse.from(toolName, saved));
    }

    /**
     * Download by exact coordinates, with the checksum verified on the way out.
     *
     * The SHA-256 travels back in both the ETag and an explicit header so a
     * client can re-verify locally. Trusting the transport is not the same as
     * verifying the content.
     */
    @GetMapping("/artifact")
    public ResponseEntity<Resource> downloadArtifact(
            @PathVariable String toolName,
            @PathVariable String version) {

        ArtifactDownload download = artifacts.download(toolName, version);
        return artifactResponse(download);
    }

    /**
     * Promotion: move the SAME bytes through the lifecycle.
     *
     * POST to a /promotion sub-resource rather than PATCHing status directly,
     * because a promotion is an event with rules and an audit trail, not a
     * free-form field edit.
     */
    @PostMapping("/promotion")
    public ToolVersionResponse promote(
            @PathVariable String toolName,
            @PathVariable String version,
            @Valid @RequestBody PromotionRequest request) {

        return ToolVersionResponse.from(toolName, registry.promote(toolName, version, request.status()));
    }

    /** Shared by this controller and the client-facing download. */
    static ResponseEntity<Resource> artifactResponse(ArtifactDownload download) {
        byte[] bytes = download.content().bytes();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + download.filename() + "\"")
                .eTag("\"" + download.content().sha256() + "\"")
                .header("X-Artifact-Sha256", download.content().sha256())
                .header("X-Artifact-Version", download.version().getVersion())
                .header("X-Artifact-Path", download.version().getArtifactPath())
                .body(new ByteArrayResource(bytes));
    }
}
