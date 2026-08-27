package com.acme.toolplatform.web.error;

import com.acme.toolplatform.service.exception.ArtifactMissingException;
import com.acme.toolplatform.service.exception.ArtifactStoreException;
import com.acme.toolplatform.service.exception.ChecksumMismatchException;
import com.acme.toolplatform.service.exception.DuplicateResourceException;
import com.acme.toolplatform.service.exception.IllegalPromotionException;
import com.acme.toolplatform.service.exception.InvalidVersionException;
import com.acme.toolplatform.service.exception.ResourceNotFoundException;
import com.acme.toolplatform.service.exception.VersionRevokedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place that turns exceptions into RFC 7807 "problem+json" responses.
 *
 * Why bother? Because the Python integration-test framework (and every other
 * client) can then assert on a STABLE machine-readable shape - `type` and
 * `status` - instead of parsing English error strings that change every sprint.
 *
 * <h2>Why this extends ResponseEntityExceptionHandler</h2>
 *
 * It did not, originally, and that was a real bug found by the Python suite.
 *
 * A bare {@code @ExceptionHandler(Exception.class)} catch-all is more specific
 * than nothing, so it wins over Spring's own DefaultHandlerExceptionResolver -
 * and quietly swallows the framework's exceptions:
 *
 * <pre>
 *   malformed JSON body   HttpMessageNotReadableException     400 -> 500
 *   wrong Content-Type    HttpMediaTypeNotSupportedException  415 -> 500
 *   unknown URL           NoResourceFoundException            404 -> 500
 *   wrong HTTP method     HttpRequestMethodNotSupported...    405 -> 500
 * </pre>
 *
 * Every client mistake was reported as a server fault. That is not cosmetic:
 * it pages the on-call engineer for somebody's typo, it makes 5xx alerting
 * useless, and it tells the caller "retry later" when retrying can never help.
 *
 * Extending {@link ResponseEntityExceptionHandler} gives Spring's own handlers
 * back their precedence (they are declared for specific types, so they beat
 * the catch-all), while {@link #handleExceptionInternal} keeps every response
 * in our problem+json shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE = "https://platform.acme.internal/errors/";

    // ------------------------------------------------------- domain failures

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException e, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "Resource Not Found", "not-found", e.getMessage(), req);
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ProblemDetail handleDuplicate(DuplicateResourceException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "Resource Already Exists", "duplicate-resource", e.getMessage(), req);
    }

    @ExceptionHandler(InvalidVersionException.class)
    public ProblemDetail handleInvalidVersion(InvalidVersionException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Version", "invalid-version", e.getMessage(), req);
    }

    /**
     * 410 Gone, not 404.
     *
     * The distinction is meaningful to the caller: 404 says "never existed,
     * check your spelling", 410 says "existed and was withdrawn, you must
     * move". Different problems, different fixes.
     */
    @ExceptionHandler(VersionRevokedException.class)
    public ProblemDetail handleRevoked(VersionRevokedException e, HttpServletRequest req) {
        return problem(HttpStatus.GONE, "Version Revoked", "version-revoked", e.getMessage(), req);
    }

    @ExceptionHandler(IllegalPromotionException.class)
    public ProblemDetail handleIllegalPromotion(IllegalPromotionException e, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "Illegal Promotion", "illegal-promotion", e.getMessage(), req);
    }

    /**
     * The next three are 502 Bad Gateway, not 404 or 500.
     *
     * 502 says: this service is fine, but the thing BEHIND it - the artifact
     * store - failed, is unreachable, or disagrees with our own records. That
     * points the on-call engineer at the right system immediately, and it
     * tells the caller that retrying later is reasonable.
     */
    @ExceptionHandler(ArtifactMissingException.class)
    public ProblemDetail handleArtifactMissing(ArtifactMissingException e, HttpServletRequest req) {
        log.error("artifact.missing path={} message={}", req.getRequestURI(), e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "Artifact Missing From Store", "artifact-missing",
                e.getMessage() + " (the registry knows this version, the store does not - "
                        + "this is a platform inconsistency, not a bad request)", req);
    }

    @ExceptionHandler(ChecksumMismatchException.class)
    public ProblemDetail handleChecksumMismatch(ChecksumMismatchException e, HttpServletRequest req) {
        log.error("artifact.checksum.mismatch path={} message={}", req.getRequestURI(), e.getMessage());
        return problem(HttpStatus.BAD_GATEWAY, "Artifact Checksum Mismatch", "checksum-mismatch",
                e.getMessage(), req);
    }

    @ExceptionHandler(ArtifactStoreException.class)
    public ProblemDetail handleArtifactStore(ArtifactStoreException e, HttpServletRequest req) {
        log.error("artifact.store.error path={} message={}", req.getRequestURI(), e.getMessage(), e);
        return problem(HttpStatus.BAD_GATEWAY, "Artifact Store Unavailable", "artifact-store-error",
                e.getMessage(), req);
    }

    /** Size limits, empty uploads, and similar caller mistakes. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", "bad-request", e.getMessage(), req);
    }

    /**
     * Genuinely unexpected failures only.
     *
     * Everything Spring knows how to classify is handled by the superclass
     * before this is reached, so a 500 from here means a real bug worth
     * looking at - which is exactly what a 500 should mean.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled.exception path={} message={}", req.getRequestURI(), e.getMessage(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "internal-error",
                "An unexpected error occurred. Quote the requestId when reporting this.", req);
    }

    // -------------------------------------------------- framework exceptions

    /**
     * Bean-validation failures on {@code @RequestBody} -> 422 with per-field detail.
     *
     * Overriding the superclass hook rather than declaring a second
     * {@code @ExceptionHandler} for the same type - two mappings for one
     * exception is an ambiguous-mapping failure at startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, "The request body failed validation");
        pd.setTitle("Validation Error");
        decorate(pd, "validation-error", request);
        pd.setProperty("errors", fieldErrors);

        return ResponseEntity.unprocessableEntity().body(pd);
    }

    /**
     * Every response the superclass produces passes through here, so the
     * framework's problem details end up with the same {@code type} URI,
     * timestamp and request id as our own.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);

        if (response != null && response.getBody() instanceof ProblemDetail pd) {
            decorate(pd, slugFor(statusCode), request);
        }
        return response;
    }

    /** Stable, documented error identifiers for the standard status codes. */
    private String slugFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad-request";
            case 404 -> "not-found";
            case 405 -> "method-not-allowed";
            case 406 -> "not-acceptable";
            case 415 -> "unsupported-media-type";
            default -> status.is4xxClientError() ? "client-error" : "server-error";
        };
    }

    // ------------------------------------------------------------- internals

    private ProblemDetail problem(HttpStatus status, String title, String slug,
                                  String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(BASE + slug));
        pd.setTitle(title);
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("requestId", MDC.get("requestId"));
        return pd;
    }

    private void decorate(ProblemDetail pd, String slug, WebRequest request) {
        pd.setType(URI.create(BASE + slug));
        if (request instanceof ServletWebRequest servletRequest) {
            pd.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
        }
        pd.setProperty("timestamp", Instant.now().toString());
        pd.setProperty("requestId", MDC.get("requestId"));
    }
}
