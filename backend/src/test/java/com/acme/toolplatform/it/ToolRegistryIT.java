package com.acme.toolplatform.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.toolplatform.repository.ToolRepository;
import com.acme.toolplatform.repository.ToolVersionRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FULL integration test: real Spring Boot app + real PostgreSQL in Docker,
 * exercised over real HTTP. Flyway runs against the container, so this also
 * proves the migrations actually apply.
 *
 * Tagged "integration" so surefire skips it during `mvn test`; failsafe runs
 * it during `mvn verify`. Named *IT so failsafe picks it up.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ToolRegistryIT {

    /**
     * @ServiceConnection wires the container's JDBC url/user/password into
     * Spring automatically - no @DynamicPropertySource boilerplate.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired ToolRepository toolRepository;
    @Autowired ToolVersionRepository versionRepository;

    @BeforeEach
    void resetAndSeed() {
        versionRepository.deleteAll();
        toolRepository.deleteAll();

        rest.postForEntity("/api/v1/tools",
                Map.of("name", "data-validator", "description", "Validates inbound data files"), Map.class);

        for (String v : new String[] {"1.0", "1.1", "1.2", "2.0"}) {
            rest.postForEntity("/api/v1/tools/data-validator/versions",
                    Map.of("version", v,
                           "artifactPath", "data-validator/" + v + "/data-validator-" + v + ".jar"),
                    Map.class);
        }
    }

    @Test
    @DisplayName("an exact existing version resolves to those exact coordinates")
    void resolvesExactVersion() {
        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/tools/data-validator/versions/1.2", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("version", "1.2");
        assertThat(response.getBody()).containsEntry("artifactPath",
                "data-validator/1.2/data-validator-1.2.jar");
        assertThat(response.getBody()).containsEntry("status", "PUBLISHED");
    }

    @Test
    @DisplayName("a version that does not exist is 404 - it must NOT silently fall back to 2.0")
    void unknownVersionIs404() {
        ResponseEntity<Map> response =
                rest.getForEntity("/api/v1/tools/data-validator/versions/999.0", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("status", 404);
        assertThat(response.getBody()).doesNotContainKey("artifactPath");
    }

    @Test
    @DisplayName("the database refuses to overwrite a published version")
    void republishingIsRejected() {
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/v1/tools/data-validator/versions",
                Map.of("version", "1.2", "artifactPath", "data-validator/1.2/TAMPERED.jar"),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // and the original coordinates are untouched
        ResponseEntity<Map> after =
                rest.getForEntity("/api/v1/tools/data-validator/versions/1.2", Map.class);
        assertThat(after.getBody()).containsEntry("artifactPath",
                "data-validator/1.2/data-validator-1.2.jar");
    }

    @Test
    @DisplayName("versions are listed newest-first using numeric ordering")
    void listsVersionsNewestFirst() {
        rest.postForEntity("/api/v1/tools/data-validator/versions",
                Map.of("version", "1.10", "artifactPath", "data-validator/1.10/data-validator-1.10.jar"),
                Map.class);

        ResponseEntity<Map[]> response =
                rest.getForEntity("/api/v1/tools/data-validator/versions", Map[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).extracting(m -> m.get("version"))
                .containsExactly("2.0", "1.10", "1.2", "1.1", "1.0");
    }

    @Test
    @DisplayName("an unregistered tool is 404, not an empty list")
    void unknownToolIs404() {
        assertThat(rest.getForEntity("/api/v1/tools/ghost-tool/versions", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the service reports healthy")
    void healthEndpointIsUp() {
        ResponseEntity<Map> health = rest.getForEntity("/actuator/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "UP");
    }
}
