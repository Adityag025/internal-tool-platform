package com.acme.toolplatform.artifact;

import com.acme.toolplatform.service.exception.ArtifactStoreException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Chooses the adapter at startup from {@code platform.artifacts.store}.
 *
 * One bean of type ArtifactStore exists; nothing downstream knows which.
 */
@Configuration
@EnableConfigurationProperties(ArtifactStoreProperties.class)
public class ArtifactStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(ArtifactStoreConfig.class);

    @Bean
    public ArtifactStore artifactStore(ArtifactStoreProperties properties) {
        ArtifactStore store = switch (properties.getStore()) {
            case FILESYSTEM -> new FilesystemArtifactStore(Path.of(properties.getFilesystem().getRoot()));
            case ARTIFACTORY -> artifactoryStore(properties.getArtifactory());
        };
        log.info("artifact.store.selected type={} target={}", properties.getStore(), store.describe());
        return store;
    }

    private ArtifactStore artifactoryStore(ArtifactStoreProperties.Artifactory cfg) {
        if (cfg.getBaseUrl().isBlank()) {
            throw new ArtifactStoreException(
                    "platform.artifacts.store=artifactory but ARTIFACTORY_URL is not set");
        }
        // Fail fast at startup rather than on the first upload at 3am.
        if (cfg.getUsername().isBlank() || cfg.getPassword().isBlank()) {
            throw new ArtifactStoreException(
                    "platform.artifacts.store=artifactory but ARTIFACTORY_USERNAME / "
                            + "ARTIFACTORY_PASSWORD are not set. Supply them as environment "
                            + "variables or CI secrets - never in application.yml.");
        }

        RestClient client = RestClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .requestFactory(timeouts())
                // Basic auth over the wire. In a real deployment this is an
                // access token, not a password, and the URL is https.
                .defaultHeaders(headers -> headers.setBasicAuth(cfg.getUsername(), cfg.getPassword()))
                .build();

        return new ArtifactoryArtifactStore(client, cfg.getBaseUrl(), cfg.getRepository());
    }

    /**
     * Always set timeouts on a call to another service.
     *
     * The default is "wait forever", which turns a slow Artifactory into a
     * pile of stuck threads and takes this service down with it.
     */
    private ClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));
        factory.setReadTimeout(java.time.Duration.ofSeconds(60));
        return factory;
    }
}
