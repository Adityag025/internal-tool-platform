package com.acme.toolplatform.service.exception;

/**
 * Maps to HTTP 502.
 *
 * The bytes in the store do not hash to the SHA-256 the registry recorded at
 * publish time. Something was corrupted or tampered with. Refuse to serve
 * them: handing over "probably fine" bytes is exactly the failure a checksum
 * exists to prevent.
 */
public class ChecksumMismatchException extends RuntimeException {
    public ChecksumMismatchException(String message) {
        super(message);
    }
}
