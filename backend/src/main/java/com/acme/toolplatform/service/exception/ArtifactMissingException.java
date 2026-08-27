package com.acme.toolplatform.service.exception;

/**
 * Maps to HTTP 502 Bad Gateway, deliberately NOT 404.
 *
 * The registry says this version exists; the artifact store disagrees. That is
 * not "the client asked for something wrong" - it is the platform being
 * internally inconsistent. A 404 here would send the consumer hunting for a
 * typo when the actual fix is on our side.
 */
public class ArtifactMissingException extends RuntimeException {
    public ArtifactMissingException(String message) {
        super(message);
    }
}
