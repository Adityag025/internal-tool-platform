package com.acme.toolplatform.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.repository.ClientRepository;
import com.acme.toolplatform.repository.ClientToolConfigurationRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The business scenario the whole platform exists for:
 * three clients that need three different versions of the same tool, at the
 * same time, without interfering with each other.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientPinningIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate rest;
    @Autowired ClientToolConfigurationRepository configRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ToolVersionRepository versionRepository;
    @Autowired ToolRepository toolRepository;

    @BeforeEach
    void seed() {
        configRepository.deleteAll();
        clientRepository.deleteAll();
        versionRepository.deleteAll();
        toolRepository.deleteAll();

        rest.postForEntity("/api/v1/tools",
                Map.of("name", "data-validator", "description", "Validates inbound data files"), Map.class);
        for (String v : new String[] {"1.0", "1.1", "1.2", "2.0"}) {
            publish(v, null);
        }
        for (String c : new String[] {"client-a", "client-b", "client-c"}) {
            rest.postForEntity("/api/v1/clients", Map.of("name", c), Map.class);
        }
    }

    private ResponseEntity<Map> publish(String version, String status) {
        Map<String, String> body = status == null
                ? Map.of("version", version,
                         "artifactPath", "data-validator/" + version + "/data-validator-" + version + ".jar")
                : Map.of("version", version,
                         "artifactPath", "data-validator/" + version + "/data-validator-" + version + ".jar",
                         "status", status);
        return rest.postForEntity("/api/v1/tools/data-validator/versions", body, Map.class);
    }

    private ResponseEntity<Map> setVersion(String client, String version) {
        return rest.exchange("/api/v1/clients/" + client + "/tools/data-validator/version",
                HttpMethod.PUT, new HttpEntity<>(Map.of("version", version)), Map.class);
    }

    private ResponseEntity<Map> resolve(String client) {
        return rest.getForEntity("/api/v1/clients/" + client + "/tools/data-validator/version", Map.class);
    }

    @Test
    @DisplayName("three clients on three versions of one tool, simultaneously")
    void threeClientsThreeVersions() {
        assertThat(setVersion("client-a", "1.0").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setVersion("client-b", "1.1").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(setVersion("client-c", "2.0").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(resolve("client-a").getBody()).containsEntry("resolvedVersion", "1.0");
        assertThat(resolve("client-b").getBody()).containsEntry("resolvedVersion", "1.1");
        assertThat(resolve("client-c").getBody()).containsEntry("resolvedVersion", "2.0");

        // and each one reports the artifact path of ITS OWN version
        assertThat(resolve("client-a").getBody())
                .containsEntry("artifactPath", "data-validator/1.0/data-validator-1.0.jar");
    }

    @Test
    @DisplayName("publishing a new version moves the LATEST client and leaves PINNED clients alone")
    void newReleaseOnlyAffectsOptedInClients() {
        setVersion("client-a", "1.0");     // pinned, audit-frozen
        setVersion("client-c", "latest");  // explicitly opted in to float

        assertThat(resolve("client-c").getBody()).containsEntry("resolvedVersion", "2.0");

        publish("3.0", null);              // a new release lands

        assertThat(resolve("client-a").getBody())
                .as("a pinned client must not move when someone else releases")
                .containsEntry("resolvedVersion", "1.0");
        assertThat(resolve("client-c").getBody())
                .as("a client that opted in to latest follows the new release")
                .containsEntry("resolvedVersion", "3.0");
    }

    @Test
    @DisplayName("rollback is one PUT - no rebuild, no artifact change")
    void rollbackIsAConfigChange() {
        setVersion("client-c", "2.0");
        assertThat(resolve("client-c").getBody()).containsEntry("resolvedVersion", "2.0");

        // 2.0 turns out to be bad -> roll client-c back
        setVersion("client-c", "1.2");

        assertThat(resolve("client-c").getBody()).containsEntry("resolvedVersion", "1.2");
        // 2.0 itself is untouched and still available to everyone else
        assertThat(rest.getForEntity("/api/v1/tools/data-validator/versions/2.0", Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("resolution reports WHY, not just WHICH")
    void resolutionReportsSelector() {
        setVersion("client-a", "2.0");
        setVersion("client-c", "latest");

        // identical resolvedVersion, completely different meaning
        assertThat(resolve("client-a").getBody())
                .containsEntry("resolvedVersion", "2.0").containsEntry("selector", "PINNED");
        assertThat(resolve("client-c").getBody())
                .containsEntry("resolvedVersion", "2.0").containsEntry("selector", "LATEST");
    }

    @Test
    @DisplayName("a client cannot be pinned to a version that does not exist")
    void cannotPinToMissingVersion() {
        assertThat(setVersion("client-a", "999.0").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // and nothing was written
        assertThat(resolve("client-a").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("an unconfigured client gets 404 with instructions, not a default version")
    void unconfiguredClientGets404() {
        ResponseEntity<Map> response = resolve("client-b");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(String.valueOf(response.getBody().get("detail")))
                .contains("has no configuration")
                .contains("PUT /api/v1/clients/client-b/tools/data-validator/version");
    }

    @Test
    @DisplayName("a REVOKED version is 410 Gone")
    void revokedVersionIsGone() {
        publish("9.9", "REVOKED");
        setVersion("client-a", "9.9");

        ResponseEntity<Map> response = resolve("client-a");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(response.getBody()).containsEntry("status", 410);
    }

    @Test
    @DisplayName("a DEPRECATED version still resolves, with a Deprecation header")
    void deprecatedStillResolves() {
        publish("0.9", "DEPRECATED");
        setVersion("client-b", "0.9");

        ResponseEntity<Map> response = resolve("client-b");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Deprecation")).isEqualTo("true");
        assertThat(response.getBody()).containsEntry("deprecated", true);
    }

    @Test
    @DisplayName("the database refuses to delete a version a client still depends on")
    void cannotDeleteAPinnedVersion() {
        setVersion("client-a", "1.0");

        ToolVersion pinned = versionRepository
                .findByToolNameAndVersion("data-validator", "1.0").orElseThrow();

        // ON DELETE RESTRICT on client_tool_configuration.pinned_version_id
        assertThatThrownBy(() -> {
            versionRepository.delete(pinned);
            versionRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DELETE removes the configuration; the client then resolves to nothing")
    void deletingConfigurationLeavesClientUnconfigured() {
        setVersion("client-a", "1.0");

        ResponseEntity<Void> deleted = rest.exchange(
                "/api/v1/clients/client-a/tools/data-validator/version",
                HttpMethod.DELETE, null, Void.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resolve("client-a").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
