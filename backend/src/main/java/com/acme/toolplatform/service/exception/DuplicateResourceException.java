package com.acme.toolplatform.service.exception;

/** Maps to HTTP 409 - an immutable resource already exists under that identity. */
public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException(String message) {
        super(message);
    }
}
