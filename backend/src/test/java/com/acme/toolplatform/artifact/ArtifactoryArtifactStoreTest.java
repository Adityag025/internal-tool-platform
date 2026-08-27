package com.acme.toolplatform.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.acme.toolplatform.service.exception.ArtifactMissingException;
import com.acme.toolplatform.service.exception.ArtifactStoreException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Verifies the Artifactory adapter speaks the right protocol, WITHOUT running
 * a 3.8 GB Artifactory container.
 *
 * This is the standard way to test an outbound HTTP integration: assert on the
 * exact request the adapter produces (method, URL, headers, body) and on how
 * it interprets each response. A live Artifactory would test JFrog's code, not
 * ours - and it would make the whole suite depend on a service being up.
 */
class ArtifactoryArtifactStoreTest {

    private static final String BASE = "http://artifactory:8082";
    private static final String REPO = "internal-tools-local";
    private static final String PATH = "data-validator/1.2/data-validator-1.2.jar";
    private static final byte[] BYTES = "jar payload".getBytes(StandardCharsets.UTF_8);
    private static final String SHA = Checksums.sha256(BYTES);

    private MockRestServiceServer server;
    private ArtifactoryArtifactStore store;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        store = new ArtifactoryArtifactStore(builder.build(), BASE, REPO);
    }

    @Test
    @DisplayName("deploy PUTs to the repository coordinates with the SHA-256 header")
    void deploySendsChecksumHeader() {
        // exists() probe first - Artifactory says 404, so the path is free
        server.expect(requestTo(BASE + "/artifactory/api/storage/" + REPO + "/" + PATH))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withResourceNotFound());

        server.expect(requestTo(BASE + "/artifactory/" + REPO + "/" + PATH))
              .andExpect(method(HttpMethod.PUT))
              // Artifactory recomputes this and rejects the deploy on mismatch,
              // so corruption in transit is caught at upload, not by a consumer.
              .andExpect(header("X-Checksum-Sha256", SHA))
              .andExpect(content().bytes(BYTES))
              .andRespond(withStatus(HttpStatus.CREATED));

        StoredArtifact stored = store.store(PATH, BYTES);

        assertThat(stored.sha256()).isEqualTo(SHA);
        assertThat(stored.sizeBytes()).isEqualTo(BYTES.length);
        server.verify();
    }

    @Test
    @DisplayName("deploying over an existing path is refused before any bytes are sent")
    void refusesOverwrite() {
        server.expect(requestTo(BASE + "/artifactory/api/storage/" + REPO + "/" + PATH))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess("{\"checksums\":{\"sha256\":\"" + SHA + "\"}}",
                      MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> store.store(PATH, BYTES))
                .isInstanceOf(ArtifactStoreException.class)
                .hasMessageContaining("immutable");

        // no PUT was issued - the expectations above are the only calls allowed
        server.verify();
    }

    @Test
    @DisplayName("download GETs the coordinates and returns the exact bytes")
    void downloadReturnsBytes() {
        server.expect(requestTo(BASE + "/artifactory/" + REPO + "/" + PATH))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(BYTES, MediaType.APPLICATION_OCTET_STREAM));

        ArtifactContent content = store.retrieve(PATH);

        assertThat(content.bytes()).isEqualTo(BYTES);
        assertThat(content.sha256()).isEqualTo(SHA);
        server.verify();
    }

    @Test
    @DisplayName("a 404 from Artifactory becomes ArtifactMissingException, not a generic failure")
    void notFoundIsTranslated() {
        server.expect(requestTo(BASE + "/artifactory/" + REPO + "/" + PATH))
              .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> store.retrieve(PATH))
                .isInstanceOf(ArtifactMissingException.class);
    }

    @Test
    @DisplayName("a 500 from Artifactory becomes ArtifactStoreException - their outage, our 502")
    void serverErrorIsTranslated() {
        server.expect(requestTo(BASE + "/artifactory/" + REPO + "/" + PATH))
              .andRespond(withServerError());

        assertThatThrownBy(() -> store.retrieve(PATH))
                .isInstanceOf(ArtifactStoreException.class);
    }

    @Test
    @DisplayName("a failed deploy is reported, never silently swallowed")
    void failedDeployThrows() {
        server.expect(requestTo(BASE + "/artifactory/api/storage/" + REPO + "/" + PATH))
              .andRespond(withResourceNotFound());
        server.expect(requestTo(BASE + "/artifactory/" + REPO + "/" + PATH))
              .andExpect(method(HttpMethod.PUT))
              .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> store.store(PATH, BYTES))
                .isInstanceOf(ArtifactStoreException.class)
                .hasMessageContaining("rejected the deploy");
    }

    @Test
    @DisplayName("exists() maps 404 to false and 200 to true")
    void existsMapsStatus() {
        server.expect(requestTo(BASE + "/artifactory/api/storage/" + REPO + "/" + PATH))
              .andRespond(withResourceNotFound());
        assertThat(store.exists(PATH)).isFalse();

        server.reset();
        server.expect(requestTo(BASE + "/artifactory/api/storage/" + REPO + "/" + PATH))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThat(store.exists(PATH)).isTrue();
    }

    @Test
    @DisplayName("describe() names the repository, so logs and health say where bytes go")
    void describesTarget() {
        assertThat(store.describe()).isEqualTo("artifactory:" + BASE + "/" + REPO);
    }
}
