package com.acme.toolplatform.service.exception;

/**
 * Maps to HTTP 410 Gone.
 *
 * 410 rather than 404: the version demonstrably existed and was withdrawn.
 * A consumer seeing 404 will suspect a typo; a consumer seeing 410 knows the
 * artifact was pulled and that it must move to another version.
 */
public class VersionRevokedException extends RuntimeException {
    public VersionRevokedException(String message) {
        super(message);
    }
}
