package com.acme.toolplatform.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acme.toolplatform.service.exception.ArtifactMissingException;
import com.acme.toolplatform.service.exception.ArtifactStoreException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemArtifactStoreTest {

    private static final String PATH = "data-validator/1.2/data-validator-1.2.jar";
    private static final byte[] BYTES = "pretend this is a jar".getBytes(StandardCharsets.UTF_8);

    @TempDir Path tempDir;
    FilesystemArtifactStore store;

    @BeforeEach
    void setUp() {
        store = new FilesystemArtifactStore(tempDir);
    }

    @Test
    @DisplayName("stores bytes at the repository coordinates and returns their SHA-256")
    void storesAtCoordinates() {
        StoredArtifact stored = store.store(PATH, BYTES);

        assertThat(Files.exists(tempDir.resolve(PATH))).isTrue();
        assertThat(stored.sizeBytes()).isEqualTo(BYTES.length);
        assertThat(stored.sha256()).isEqualTo(Checksums.sha256(BYTES)).hasSize(64);
    }

    @Test
    @DisplayName("round-trips the exact bytes")
    void roundTrips() {
        store.store(PATH, BYTES);

        ArtifactContent content = store.retrieve(PATH);

        assertThat(content.bytes()).isEqualTo(BYTES);
        assertThat(content.sha256()).isEqualTo(Checksums.sha256(BYTES));
    }

    @Test
    @DisplayName("refuses to overwrite an existing artifact - immutability at the storage layer")
    void refusesOverwrite() {
        store.store(PATH, BYTES);

        assertThatThrownBy(() -> store.store(PATH, "tampered".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ArtifactStoreException.class)
                .hasMessageContaining("immutable");

        // and the original bytes are untouched
        assertThat(store.retrieve(PATH).bytes()).isEqualTo(BYTES);
    }

    @Test
    @DisplayName("a missing artifact is reported, not silently empty")
    void missingArtifactThrows() {
        assertThatThrownBy(() -> store.retrieve("ghost/9.9/ghost-9.9.jar"))
                .isInstanceOf(ArtifactMissingException.class);
        assertThat(store.exists("ghost/9.9/ghost-9.9.jar")).isFalse();
    }

    @Test
    @DisplayName("path traversal cannot escape the artifact root")
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> store.store("../../../etc/pwned", BYTES))
                .isInstanceOf(ArtifactStoreException.class)
                .hasMessageContaining("outside the artifact root");
    }

    @Test
    @DisplayName("two versions of the same tool live side by side")
    void versionsCoexist() {
        store.store("data-validator/1.2/data-validator-1.2.jar", "v12".getBytes(StandardCharsets.UTF_8));
        store.store("data-validator/2.0/data-validator-2.0.jar", "v20".getBytes(StandardCharsets.UTF_8));

        assertThat(store.retrieve("data-validator/1.2/data-validator-1.2.jar").bytes())
                .isEqualTo("v12".getBytes(StandardCharsets.UTF_8));
        assertThat(store.retrieve("data-validator/2.0/data-validator-2.0.jar").bytes())
                .isEqualTo("v20".getBytes(StandardCharsets.UTF_8));
    }
}
