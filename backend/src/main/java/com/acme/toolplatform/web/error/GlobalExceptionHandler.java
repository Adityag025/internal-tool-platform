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
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One place that turns exceptions into RFC 7807 "problem+json" responses.
 *
 * Why bother? Because the Python integration-test framework (and every other
 * client) can then assert on a STABLE machine-readable shape - `type` and
 * `status` - instead of parsing English error strings that change every sprint.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BASE = "https://platform.acme.internal/errors/";

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

    /** Bean-validation failures on @RequestBody -> 422 with per-field detail. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        List<Map<String, String>> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage())))
                .toList();
        ProblemDetail pd = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation Error", "validation-error",
                "The request body failed validation", req);
        pd.setProperty("errors", fieldErrors);
        return pd;
    }

    /** Anything unexpected: log the stack trace, return a generic body. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled.exception path={} message={}", req.getRequestURI(), e.getMessage(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "internal-error",
                "An unexpected error occurred. Quote the requestId when reporting this.", req);
    }

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
}
