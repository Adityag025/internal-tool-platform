package com.acme.toolplatform.service.exception;

/** Maps to HTTP 400 - the version string is malformed (e.g. "1.x", "latest"). */
public class InvalidVersionException extends RuntimeException {
    public InvalidVersionException(String message) {
        super(message);
    }
}
