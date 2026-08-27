package com.acme.toolplatform.artifact;

import com.acme.toolplatform.service.exception.ArtifactMissingException;
import com.acme.toolplatform.service.exception.ArtifactStoreException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter: a directory tree that mirrors the repository layout.
 *
 *   data/artifacts/data-validator/1.2/data-validator-1.2.jar
 *
 * Used by local development and by CI, so neither needs a running Artifactory.
 */
public class FilesystemArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemArtifactStore.class);

    private final Path root;

    public FilesystemArtifactStore(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new ArtifactStoreException("Cannot initialise artifact root " + root, e);
        }
    }

    @Override
    public StoredArtifact store(String path, byte[] content) {
        Path target = resolveSafely(path);
        if (Files.exists(target)) {
            // Immutability, enforced a second time at the storage layer. The
            // registry already refuses duplicates; this catches anything that
            // reaches the store by another route.
            throw new ArtifactStoreException("Artifact already exists and is immutable: " + path);
        }
        try {
            Files.createDirectories(target.getParent());
            // Write to a temp file and move: a crash mid-write must not leave a
            // truncated artifact sitting at the real coordinates.
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ArtifactStoreException("Failed to store artifact " + path, e);
        }
        String sha256 = Checksums.sha256(content);
        log.info("artifact.stored store=filesystem path={} bytes={} sha256={}", path, content.length, sha256);
        return new StoredArtifact(path, sha256, content.length);
    }

    @Override
    public ArtifactContent retrieve(String path) {
        Path target = resolveSafely(path);
        if (!Files.isRegularFile(target)) {
            throw new ArtifactMissingException("Artifact not present in the store: " + path);
        }
        try {
            byte[] bytes = Files.readAllBytes(target);
            return new ArtifactContent(path, Checksums.sha256(bytes), bytes.length, bytes);
        } catch (IOException e) {
            throw new ArtifactStoreException("Failed to read artifact " + path, e);
        }
    }

    @Override
    public boolean exists(String path) {
        return Files.isRegularFile(resolveSafely(path));
    }

    @Override
    public String describe() {
        return "filesystem:" + root;
    }

    /**
     * Path traversal guard.
     *
     * A crafted path like "../../etc/passwd" would otherwise escape the root.
     * Normalising and then asserting the result is still under the root is the
     * standard defence, and it belongs here - at the boundary - not only in
     * request validation, because this method is reachable from anywhere.
     */
    private Path resolveSafely(String path) {
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root)) {
            throw new ArtifactStoreException("Rejected path outside the artifact root: " + path);
        }
        return resolved;
    }
}
