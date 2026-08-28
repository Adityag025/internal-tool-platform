package com.acme.toolplatform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates write operations with a shared API key.
 *
 * <h2>Why an API key and not OAuth2/JWT</h2>
 *
 * The callers here are machines: a CI pipeline publishing a build, and an
 * operator changing a client's pin. There is no user to redirect to a login
 * page and no session to maintain, so the ceremony of an authorization-code
 * flow buys nothing. A key in a secret store is the right size of solution.
 *
 * A real deployment would go further - per-caller keys so they can be revoked
 * and attributed individually, an expiry, and scopes so the CI token can
 * publish but not re-pin a client. Those are additive; the shape does not
 * change.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    public static final String HEADER = "X-API-Key";

    private final byte[] expectedKey;

    public ApiKeyAuthFilter(String expectedKey) {
        this.expectedKey = expectedKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String presented = request.getHeader(HEADER);

        if (presented != null && matches(presented)) {
            // A successful authentication with no roles beyond the marker one.
            // Scopes would be added here.
            var authentication = new UsernamePasswordAuthenticationToken(
                    "ci-publisher", null, AuthorityUtils.createAuthorityList("ROLE_PUBLISHER"));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } else if (presented != null) {
            // Log the ATTEMPT, never the key. Logging a rejected credential is
            // how secrets end up in a log aggregator that half the company can read.
            log.warn("auth.rejected reason=bad-api-key method={} path={}",
                    request.getMethod(), request.getRequestURI());
        }

        chain.doFilter(request, response);
    }

    /**
     * Constant-time comparison.
     *
     * {@code String.equals} short-circuits on the first differing byte, so the
     * time it takes leaks how many leading characters were correct. That is a
     * timing oracle: an attacker can recover the key one character at a time.
     * {@code MessageDigest.isEqual} compares every byte regardless.
     */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedKey);
    }

    /**
     * Only CORS preflight is skipped.
     *
     * This originally skipped GET as well, on the reasoning that "reads are
     * public" - and that was a bug caught by SecurityIT. Skipping GET means a
     * GET can never be AUTHENTICATED, so /actuator/prometheus returned 401
     * even when a valid key was presented.
     *
     * The lesson generalises: authentication answers "who are you?" and
     * authorization answers "may you?". This filter must only do the first,
     * for every request. Encoding the read/write decision here as well as in
     * the authorization rules put the same policy in two places, and they
     * disagreed.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equals(request.getMethod());
    }
}
