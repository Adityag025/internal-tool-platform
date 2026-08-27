package com.acme.toolplatform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.toolplatform.artifact.Checksums;
import com.acme.toolplatform.repository.ClientRepository;
import com.acme.toolplatform.repository.ClientToolConfigurationRepository;
import com.acme.toolplatform.repository.ToolRepository;
import com.acme.toolplatform.repository.ToolVersionRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The full artifact lifecycle over real HTTP and a real database:
 * register -> DRAFT -> upload bytes -> promote -> pin -> client downloads.
 *
 * Runs against the FILESYSTEM adapter, so it needs no Artifactory. That is the
 * payoff of the ArtifactStore port: CI verifies the whole distribution path
 * without depending on an external service being up.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ArtifactDistributionIT {

    private static final String TOOL = "data-validator";
    private static final byte[] JAR = "pretend jar payload".getBytes(StandardCharsets.UTF_8);
    private static final String JAR_SHA = Checksums.sha256(JAR);

    static Path artifactRoot;

    static {
        try {
            artifactRoot = Files.createTempDirectory("tool-platform-it-artifacts");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void artifactStore(DynamicPropertyRegistry registry) {
        registry.add("platform.artifacts.store", () -> "filesystem");
        registry.add("platform.artifacts.filesystem.root", () -> artifactRoot.toString());
    }

    @Autowired TestRestTemplate rest;
    @Autowired ToolVersionRepository versionRepository;
    @Autowired ToolRepository toolRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ClientToolConfigurationRepository configRepository;

    @BeforeEach
    void reset() throws IOException {
        configRepository.deleteAll();
        clientRepository.deleteAll();
        versionRepository.deleteAll();
        toolRepository.deleteAll();
        clearArtifactRoot();

        rest.postForEntity("/api/v1/tools", Map.of("name", TOOL, "description", "Validates data"), Map.class);
    }

    private void clearArtifactRoot() throws IOException {
        if (!Files.exists(artifactRoot)) {
            return;
        }
        try (var paths = Files.walk(artifactRoot)) {
            paths.sorted(Comparator.reverseOrder())
                 .filter(p -> !p.equals(artifactRoot))
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException ignored) {
                         // best effort in a test fixture
                     }
                 });
        }
    }

    // ------------------------------------------------------------- helpers

    private String pathOf(String version) {
        return TOOL + "/" + version + "/" + TOOL + "-" + version + ".jar";
    }

    private ResponseEntity<Map> registerVersion(String version, String status) {
        return rest.postForEntity("/api/v1/tools/" + TOOL + "/versions",
                Map.of("version", version, "artifactPath", pathOf(version), "status", status), Map.class);
    }

    private ResponseEntity<Map> upload(String version, byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return rest.exchange("/api/v1/tools/" + TOOL + "/versions/" + version + "/artifact",
                HttpMethod.PUT, new HttpEntity<>(bytes, headers), Map.class);
    }

    private ResponseEntity<Map> promote(String version, String status) {
        return rest.postForEntity("/api/v1/tools/" + TOOL + "/versions/" + version + "/promotion",
                Map.of("status", status), Map.class);
    }

    private ResponseEntity<byte[]> download(String version) {
        return rest.getForEntity("/api/v1/tools/" + TOOL + "/versions/" + version + "/artifact", byte[].class);
    }

    // --------------------------------------------------------------- tests

    @Test
    @DisplayName("the release pipeline: DRAFT -> upload -> promote -> download")
    void fullReleaseFlow() {
        assertThat(registerVersion("1.2", "DRAFT").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // a DRAFT with no bytes cannot be published - the release gate
        assertThat(promote("1.2", "PUBLISHED").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> uploaded = upload("1.2", JAR);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(uploaded.getBody()).containsEntry("checksumSha256", JAR_SHA);

        assertThat(promote("1.2", "PUBLISHED").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<byte[]> downloaded = download("1.2");
        assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloaded.getBody()).isEqualTo(JAR);
        assertThat(downloaded.getHeaders().getFirst("X-Artifact-Sha256")).isEqualTo(JAR_SHA);
        assertThat(downloaded.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("data-validator-1.2.jar");
    }

    @Test
    @DisplayName("bytes are written at the repository coordinates on disk")
    void bytesLandAtCoordinates() {
        registerVersion("1.2", "DRAFT");
        upload("1.2", JAR);

        assertThat(artifactRoot.resolve(pathOf("1.2"))).exists();
    }

    @Test
    @DisplayName("an uploaded artifact can never be replaced")
    void artifactIsImmutable() {
        registerVersion("1.2", "DRAFT");
        upload("1.2", JAR);

        ResponseEntity<Map> second = upload("1.2", "TAMPERED".getBytes(StandardCharsets.UTF_8));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(download("1.2").getBody()).as("original bytes survive").isEqualTo(JAR);
    }

    @Test
    @DisplayName("a client downloads its pinned version without naming a version")
    void clientDownloadsItsOwnVersion() {
        registerVersion("1.2", "PUBLISHED");
        upload("1.2", JAR);
        byte[] v20 = "version two payload".getBytes(StandardCharsets.UTF_8);
        registerVersion("2.0", "PUBLISHED");
        upload("2.0", v20);

        rest.postForEntity("/api/v1/clients", Map.of("name", "client-a"), Map.class);
        rest.exchange("/api/v1/clients/client-a/tools/" + TOOL + "/version",
                HttpMethod.PUT, new HttpEntity<>(Map.of("version", "1.2")), Map.class);

        ResponseEntity<byte[]> response =
                rest.getForEntity("/api/v1/clients/client-a/tools/" + TOOL + "/artifact", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).as("client-a gets 1.2, not the newest").isEqualTo(JAR);
        assertThat(response.getHeaders().getFirst("X-Artifact-Version")).isEqualTo("1.2");
    }

    @Test
    @DisplayName("corrupted bytes on disk are refused with 502, not served")
    void corruptedArtifactIsRefused() throws IOException {
        registerVersion("1.2", "PUBLISHED");
        upload("1.2", JAR);

        // simulate corruption / tampering underneath the store
        Files.write(artifactRoot.resolve(pathOf("1.2")), "corrupted".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/tools/" + TOOL + "/versions/1.2/artifact", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry(
                "type", "https://platform.acme.internal/errors/checksum-mismatch");
    }

    @Test
    @DisplayName("registry/store drift is 502, not 404 - it is our bug, not the caller's")
    void missingBytesAre502() throws IOException {
        registerVersion("1.2", "PUBLISHED");
        upload("1.2", JAR);
        Files.delete(artifactRoot.resolve(pathOf("1.2")));

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/tools/" + TOOL + "/versions/1.2/artifact", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).containsEntry(
                "type", "https://platform.acme.internal/errors/artifact-missing");
    }

    @Test
    @DisplayName("promotion moves the SAME bytes - the checksum never changes")
    void promotionDoesNotChangeBytes() {
        registerVersion("1.2", "DRAFT");
        upload("1.2", JAR);

        promote("1.2", "PUBLISHED");
        String afterPublish = (String) rest.getForEntity(
                "/api/v1/tools/" + TOOL + "/versions/1.2", Map.class).getBody().get("checksumSha256");

        promote("1.2", "DEPRECATED");
        String afterDeprecate = (String) rest.getForEntity(
                "/api/v1/tools/" + TOOL + "/versions/1.2", Map.class).getBody().get("checksumSha256");

        assertThat(afterPublish).isEqualTo(JAR_SHA);
        assertThat(afterDeprecate).isEqualTo(JAR_SHA);
        assertThat(download("1.2").getBody()).isEqualTo(JAR);
    }

    @Test
    @DisplayName("a REVOKED artifact is 410 and its bytes are never served again")
    void revokedArtifactIsGone() {
        registerVersion("1.2", "PUBLISHED");
        upload("1.2", JAR);

        assertThat(promote("1.2", "REVOKED").getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/tools/" + TOOL + "/versions/1.2/artifact", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    @DisplayName("the artifact store is reported in /actuator/health")
    void healthReportsArtifactStore() {
        ResponseEntity<Map> health = rest.getForEntity("/actuator/health", Map.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map components = (Map) health.getBody().get("components");
        assertThat(components).containsKey("artifactStore");
    }
}
