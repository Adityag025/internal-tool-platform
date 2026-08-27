package com.acme.toolplatform.artifact;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the artifact store in /actuator/health.
 *
 * The service can be "up" while being useless because it cannot reach the
 * artifact store. A health check that only proves the JVM is running is not
 * a health check.
 */
@Component
public class ArtifactStoreHealthIndicator implements HealthIndicator {

    private final ArtifactStore store;

    public ArtifactStoreHealthIndicator(ArtifactStore store) {
        this.store = store;
    }

    @Override
    public Health health() {
        try {
            // A cheap probe for a path that will never exist: proves the store
            // is reachable and answering without depending on any content.
            store.exists(".health-probe");
            return Health.up().withDetail("store", store.describe()).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("store", store.describe()).build();
        }
    }
}
