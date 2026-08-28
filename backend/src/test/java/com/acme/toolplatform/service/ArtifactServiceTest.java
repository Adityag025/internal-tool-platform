package com.acme.toolplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.toolplatform.artifact.ArtifactContent;
import com.acme.toolplatform.artifact.ArtifactStore;
import com.acme.toolplatform.artifact.ArtifactStoreProperties;
import com.acme.toolplatform.artifact.Checksums;
import com.acme.toolplatform.artifact.StoredArtifact;
import com.acme.toolplatform.domain.SemanticVersion;
import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.observability.PlatformMetrics;
import com.acme.toolplatform.repository.ToolVersionRepository;
import com.acme.toolplatform.service.exception.ChecksumMismatchException;
import com.acme.toolplatform.service.exception.DuplicateResourceException;
import com.acme.toolplatform.service.exception.VersionRevokedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    private static final String TOOL = "data-validator";
    private static final String PATH = "data-validator/1.2/data-validator-1.2.jar";
    private static final byte[] BYTES = "jar bytes".getBytes(StandardCharsets.UTF_8);
    private static final String SHA = Checksums.sha256(BYTES);

    @Mock ArtifactStore store;
    @Mock ToolRegistryService registry;
    @Mock ClientConfigurationService clients;
    @Mock ToolVersionRepository versionRepository;

    ArtifactService service;
    Tool tool;
    // A REAL in-memory registry rather than a mock: metrics then behave
    // exactly as in production and can be asserted on, which a mock cannot do.
    SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new ArtifactService(store, registry, clients, versionRepository,
                new ArtifactStoreProperties(), new PlatformMetrics(meterRegistry));
        tool = new Tool(TOOL, null);
    }

    private ToolVersion version(VersionStatus status) {
        return new ToolVersion(tool, SemanticVersion.parse("1.2"), PATH, null, status);
    }

    @Test
    @DisplayName("uploading seals the version with the checksum of the stored bytes")
    void uploadSealsChecksum() {
        ToolVersion v = version(VersionStatus.DRAFT);
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(v);
        when(store.store(PATH, BYTES)).thenReturn(new StoredArtifact(PATH, SHA, BYTES.length));

        ToolVersion saved = service.uploadArtifact(TOOL, "1.2", BYTES);

        assertThat(saved.getChecksumSha256()).isEqualTo(SHA);
        assertThat(saved.hasArtifact()).isTrue();
        verify(versionRepository).save(v);
    }

    @Test
    @DisplayName("a version that already has bytes cannot receive new ones")
    void cannotReuploadArtifact() {
        ToolVersion v = version(VersionStatus.PUBLISHED);
        v.sealWith(SHA);
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(v);

        assertThatThrownBy(() -> service.uploadArtifact(TOOL, "1.2", "different".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("immutable");

        verify(store, never()).store(anyString(), any());
    }

    @Test
    @DisplayName("a REVOKED version cannot receive bytes")
    void revokedCannotReceiveBytes() {
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(version(VersionStatus.REVOKED));

        assertThatThrownBy(() -> service.uploadArtifact(TOOL, "1.2", BYTES))
                .isInstanceOf(VersionRevokedException.class);
    }

    @Test
    @DisplayName("an empty upload is rejected")
    void rejectsEmptyUpload() {
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(version(VersionStatus.DRAFT));

        assertThatThrownBy(() -> service.uploadArtifact(TOOL, "1.2", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("download verifies the stored bytes against the recorded checksum")
    void downloadVerifiesChecksum() {
        ToolVersion v = version(VersionStatus.PUBLISHED);
        v.sealWith(SHA);
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(v);
        when(store.retrieve(PATH)).thenReturn(new ArtifactContent(PATH, SHA, BYTES.length, BYTES));

        ArtifactDownload download = service.download(TOOL, "1.2");

        assertThat(download.content().bytes()).isEqualTo(BYTES);
        assertThat(download.filename()).isEqualTo("data-validator-1.2.jar");
    }

    @Test
    @DisplayName("corrupted bytes are refused, not served")
    void refusesCorruptedBytes() {
        ToolVersion v = version(VersionStatus.PUBLISHED);
        v.sealWith(SHA);
        byte[] corrupted = "tampered".getBytes(StandardCharsets.UTF_8);
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(v);
        when(store.retrieve(PATH))
                .thenReturn(new ArtifactContent(PATH, Checksums.sha256(corrupted), corrupted.length, corrupted));

        assertThatThrownBy(() -> service.download(TOOL, "1.2"))
                .isInstanceOf(ChecksumMismatchException.class)
                .hasMessageContaining("registry recorded");

        // A checksum mismatch gets its own counter because it should page
        // someone - the correct alert threshold for it is "greater than zero".
        assertThat(meterRegistry.counter("toolplatform.artifact.checksum.mismatch", "tool", TOOL).count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a REVOKED version is never downloadable, even by exact coordinates")
    void revokedIsNotDownloadable() {
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(version(VersionStatus.REVOKED));

        assertThatThrownBy(() -> service.download(TOOL, "1.2"))
                .isInstanceOf(VersionRevokedException.class);
        verify(store, never()).retrieve(anyString());
    }
}
