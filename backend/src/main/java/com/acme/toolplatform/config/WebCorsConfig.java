package com.acme.toolplatform.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the small TypeScript dashboard.
 *
 * The browser enforces the same-origin policy: a page served from
 * http://localhost:3000 may not read a response from http://localhost:8081
 * unless that response says it is allowed to. CORS is the server granting that
 * permission - it is a browser rule, not a network control. Nothing here stops
 * curl, the Python suite, or any server-side caller.
 *
 * Two deliberate choices:
 *
 *   - The allowed origins are CONFIGURED, never "*". A wildcard is
 *     incompatible with credentials, and once this API is authenticated a
 *     wildcard would let any site on the internet make authenticated requests
 *     with a logged-in user's cookies.
 *
 *   - Only the methods and headers actually used are exposed. The custom
 *     artifact headers must be listed in exposedHeaders, or the browser hides
 *     them from JavaScript even though they arrive - which would silently
 *     break the client's checksum verification.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebCorsConfig.class);

    private final List<String> allowedOrigins;

    public WebCorsConfig(@Value("${platform.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            log.info("cors.disabled reason=no-allowed-origins-configured");
            return;
        }
        log.info("cors.enabled origins={}", allowedOrigins);

        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Client-Id", "X-Request-Id")
                // Without this the browser receives these headers but refuses
                // to let JavaScript read them.
                .exposedHeaders("X-Artifact-Sha256", "X-Artifact-Version",
                                "X-Artifact-Path", "X-Request-Id", "ETag", "Deprecation")
                .maxAge(3600);
    }
}
