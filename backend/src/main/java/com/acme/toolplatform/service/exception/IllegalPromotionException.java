package com.acme.toolplatform.service.exception;

/** Maps to HTTP 409 - the requested status transition is not allowed. */
public class IllegalPromotionException extends RuntimeException {
    public IllegalPromotionException(String message) {
        super(message);
    }
}
