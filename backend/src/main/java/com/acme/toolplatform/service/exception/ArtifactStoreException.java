package com.acme.toolplatform.service.exception;

/** Maps to HTTP 502 - the artifact store itself failed or is unreachable. */
public class ArtifactStoreException extends RuntimeException {
    public ArtifactStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public ArtifactStoreException(String message) {
        super(message);
    }
}
