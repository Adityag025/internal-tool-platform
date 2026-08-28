package com.acme.toolplatform.security;

import com.acme.toolplatform.web.error.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Read-open, write-authenticated.
 *
 * <h2>The threat model, stated plainly</h2>
 *
 * This is an internal platform. Reading which versions exist is not sensitive
 * and every consumer needs it constantly, so reads are public. WRITING is what
 * matters: publishing an artifact or re-pinning a client changes what runs on
 * other people's machines. Those need a credential.
 *
 * <h2>Why it can be switched off, and why that is loud</h2>
 *
 * With no key configured the service runs unauthenticated, because forcing a
 * credential into every local `curl` and every test would get the whole
 * mechanism disabled by someone in a hurry. But it logs a WARNING every
 * start-up and reports itself in /actuator/info. A silent insecure default is
 * how you end up in production without noticing; a loud one is a decision.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final String apiKey;

    public SecurityConfig(@Value("${platform.security.api-key:}") String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public boolean isAuthenticationEnabled() {
        return !apiKey.isEmpty();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protects browser form posts that rely on ambient cookie
            // credentials. This API is stateless and authenticates with an
            // explicit header, which a cross-site request cannot set - so CSRF
            // tokens would add ceremony and no protection.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Delegates to WebCorsConfig rather than defining a second policy.
            .cors(Customizer.withDefaults())
            .headers(h -> h
                .frameOptions(f -> f.deny())
                .contentTypeOptions(Customizer.withDefaults()));

        if (!isAuthenticationEnabled()) {
            log.warn("=".repeat(78));
            log.warn("SECURITY: platform.security.api-key is not set - WRITES ARE UNAUTHENTICATED.");
            log.warn("          Anyone who can reach this service can publish artifacts and");
            log.warn("          re-pin clients. Acceptable locally; never in a shared environment.");
            log.warn("          Set API_KEY (env) to enable authentication.");
            log.warn("=".repeat(78));
            return http.authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }

        log.info("security.enabled mode=api-key protected=POST,PUT,DELETE");

        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                // Metrics name internal endpoints and traffic patterns. Public
                // by default is an information leak, so they need the key too.
                .requestMatchers("/actuator/**").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/**").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/**").authenticated()
                .anyRequest().denyAll())
            .addFilterBefore(new ApiKeyAuthFilter(apiKey), UsernamePasswordAuthenticationFilter.class)
            // Without this, an unauthenticated write returns Spring Security's
            // default HTML page - breaking the problem+json contract that every
            // client and the whole test suite assert on.
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> writeProblem(res, 401,
                        "Unauthorized", "unauthorized",
                        "This operation requires the " + ApiKeyAuthFilter.HEADER + " header"))
                .accessDeniedHandler((req, res, ex) -> writeProblem(res, 403,
                        "Forbidden", "forbidden",
                        "The presented credential is not permitted to perform this operation")));

        return http.build();
    }

    private static void writeProblem(HttpServletResponse response, int status, String title,
                                     String slug, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Without this the servlet default is ISO-8859-1, so the response
        // advertises `application/problem+json;charset=ISO-8859-1` and any
        // non-ASCII character in a message is mangled. JSON is UTF-8.
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"tool-platform\"");
        response.getWriter().write("""
            {"type":"%s%s","title":"%s","status":%d,"detail":"%s"}"""
                .formatted(GlobalExceptionHandler.ERROR_BASE, slug, title, status, detail));
    }
}
