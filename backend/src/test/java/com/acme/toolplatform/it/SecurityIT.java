package com.acme.toolplatform.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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
 * Read-open, write-authenticated - verified with authentication actually ON.
 *
 * The rest of the suite runs with no API key configured, which is the local
 * default. This class sets one, so the security configuration is exercised
 * rather than assumed. A security control nobody tests is a security control
 * nobody has.
 */
@Tag("integration")
@Testcontainers
/*
 * Spring Boot DISABLES metrics export in tests by default
 * (DisableObservabilityContextCustomizer), so no PrometheusMeterRegistry bean
 * is created and /actuator/prometheus is simply not registered - it 404s,
 * which looks exactly like a configuration mistake. The default is sensible:
 * it stops every test run from pushing metrics to a real backend. This
 * annotation opts back in for a test whose whole point is the metrics endpoint.
 */
@AutoConfigureObservability
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "platform.security.api-key=test-key-do-not-use-anywhere-real")
class SecurityIT {

    private static final String KEY = "test-key-do-not-use-anywhere-real";
    private static final String TOOL = "secured-tool";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /*
     * A per-run artifact root.
     *
     * Without this the test writes into the real ./data/artifacts directory,
     * which survives between runs - so the SECOND run's upload is correctly
     * refused as an immutable duplicate, the publish counter never increments,
     * and the metrics assertion fails for a reason that has nothing to do with
     * metrics. Exactly the isolation problem the Python suite solves with
     * run-scoped names: a test that writes to a shared persistent location is
     * not re-runnable.
     */
    static Path artifactRoot;

    static {
        try {
            artifactRoot = Files.createTempDirectory("security-it-artifacts");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void isolatedArtifactStore(DynamicPropertyRegistry registry) {
        registry.add("platform.artifacts.filesystem.root", () -> artifactRoot.toString());
    }

    @Autowired TestRestTemplate rest;

    private ResponseEntity<Map> post(String path, Object body, String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null) {
            headers.set("X-API-Key", apiKey);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    @DisplayName("an unauthenticated write is 401, in problem+json")
    void unauthenticatedWriteIsRejected() {
        ResponseEntity<Map> response = post("/api/v1/tools", Map.of("name", "unauthorised-tool"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The error contract must hold for auth failures too - clients and the
        // Python suite assert on the machine-readable shape, not on HTML.
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getBody()).containsEntry(
                "type", "https://platform.acme.internal/errors/unauthorized");
        // Tells a well-behaved client HOW to authenticate.
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("ApiKey");
    }

    @Test
    @DisplayName("a wrong key is rejected exactly like no key - no oracle")
    void wrongKeyIsRejected() {
        ResponseEntity<Map> response =
                post("/api/v1/tools", Map.of("name", "unauthorised-tool"), "not-the-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Deliberately indistinguishable from the no-key case: a different
        // status or message would confirm to an attacker that a key format or
        // prefix was correct.
    }

    @Test
    @DisplayName("the correct key is accepted")
    void correctKeyIsAccepted() {
        ResponseEntity<Map> response = post("/api/v1/tools",
                Map.of("name", TOOL, "description", "Created with a valid key"), KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("reads stay public - every consumer needs them constantly")
    void readsAreUnauthenticated() {
        assertThat(rest.getForEntity("/api/v1/tools", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("DELETE is protected too, not just POST")
    void deleteIsProtected() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/clients/anyone/tools/anything/version",
                HttpMethod.DELETE, new HttpEntity<>(new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("health stays public so orchestrators can probe it")
    void healthIsPublic() {
        ResponseEntity<Map> health = rest.getForEntity("/actuator/health", Map.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        // ECS, Kubernetes and load balancers cannot present a credential.
    }

    @Test
    @DisplayName("metrics are NOT public - they leak endpoints and traffic shape")
    void metricsRequireTheKey() {
        assertThat(rest.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", KEY);
        ResponseEntity<String> authorised = rest.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(authorised.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(authorised.getBody()).contains("jvm_memory_used_bytes");
    }

    @Test
    @DisplayName("the platform's own metrics appear once traffic has flowed")
    void customMetricsArePublished() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-API-Key", KEY);

        // generate one publish so the counter exists
        post("/api/v1/tools", Map.of("name", "metrics-tool"), KEY);
        post("/api/v1/tools/metrics-tool/versions",
                Map.of("version", "1.0", "artifactPath", "metrics-tool/1.0/metrics-tool-1.0.jar"), KEY);
        rest.exchange("/api/v1/tools/metrics-tool/versions/1.0/artifact", HttpMethod.PUT,
                new HttpEntity<>("bytes".getBytes(), authHeaders(KEY)), Map.class);

        String scrape = rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers), String.class).getBody();

        assertThat(scrape).contains("toolplatform_version_published_total");
    }

    private HttpHeaders authHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set("X-API-Key", key);
        return headers;
    }
}
