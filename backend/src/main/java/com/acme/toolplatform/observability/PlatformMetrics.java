package com.acme.toolplatform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The platform's own metrics, in one place.
 *
 * <h2>Tag cardinality is the thing to get right</h2>
 *
 * A Prometheus time series is created for every unique combination of tag
 * values, and each one costs memory in the process and in the scraper. So
 * these metrics are tagged by TOOL (a handful, bounded) and by OUTCOME (a
 * fixed enum) - and never by VERSION, CLIENT, or request id.
 *
 * Tagging by version would create a new series for every release, forever:
 * the classic cardinality explosion that takes down a monitoring stack. If you
 * need per-version detail, that is a question for logs, which are cheap to
 * write and queried on demand. Metrics answer "how often, how fast, how bad";
 * logs answer "what exactly happened in this one case".
 */
@Component
public class PlatformMetrics {

    private final MeterRegistry registry;

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Outcomes are a closed set, so they are safe as a tag. */
    public enum Outcome { SUCCESS, NOT_FOUND, REVOKED, CHECKSUM_MISMATCH, STORE_ERROR }

    /** How often each client resolves a version, and how it was selected. */
    public void versionResolved(String tool, String selector, Outcome outcome) {
        Counter.builder("toolplatform.version.resolutions")
                .description("Version resolutions by tool, selector and outcome")
                .tag("tool", tool)
                .tag("selector", selector)
                .tag("outcome", outcome.name().toLowerCase())
                .register(registry)
                .increment();
    }

    /** Artifact downloads: count, outcome, and how long the bytes took. */
    public void artifactDownloaded(String tool, Outcome outcome, Duration took, long bytes) {
        Timer.builder("toolplatform.artifact.download")
                .description("Time to fetch and verify an artifact")
                .tag("tool", tool)
                .tag("outcome", outcome.name().toLowerCase())
                // Percentiles, not just a mean. A mean latency hides the tail,
                // and the tail is what users actually complain about.
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(took);

        if (outcome == Outcome.SUCCESS) {
            registry.counter("toolplatform.artifact.bytes.served", "tool", tool).increment(bytes);
        }
    }

    /** Publishes, so you can see release frequency per tool. */
    public void versionPublished(String tool) {
        registry.counter("toolplatform.version.published", "tool", tool).increment();
    }

    /**
     * A checksum mismatch is its own counter, deliberately.
     *
     * It is buried inside the download error rate otherwise, and it is not an
     * ordinary error - it means stored bytes no longer match what was
     * published. This is the one metric here that should page someone: the
     * correct alert threshold is "greater than zero".
     */
    public void checksumMismatch(String tool) {
        registry.counter("toolplatform.artifact.checksum.mismatch", "tool", tool).increment();
    }

    /** Rejected credentials, for spotting a misconfigured client or a probe. */
    public void authRejected() {
        registry.counter("toolplatform.auth.rejected").increment();
    }
}
