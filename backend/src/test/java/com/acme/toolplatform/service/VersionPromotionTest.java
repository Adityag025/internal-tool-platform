package com.acme.toolplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.toolplatform.domain.SemanticVersion;
import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.repository.ToolRepository;
import com.acme.toolplatform.repository.ToolVersionRepository;
import com.acme.toolplatform.service.exception.IllegalPromotionException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The promotion state machine: same bytes, different lifecycle stage. */
@ExtendWith(MockitoExtension.class)
class VersionPromotionTest {

    private static final String TOOL = "data-validator";

    @Mock ToolRepository toolRepository;
    @Mock ToolVersionRepository versionRepository;

    ToolRegistryService service;
    Tool tool;

    @BeforeEach
    void setUp() {
        service = new ToolRegistryService(toolRepository, versionRepository);
        tool = new Tool(TOOL, null);
    }

    private ToolVersion existing(String raw, VersionStatus status, boolean withArtifact) {
        ToolVersion v = new ToolVersion(tool, SemanticVersion.parse(raw),
                TOOL + "/" + raw + "/" + TOOL + "-" + raw + ".jar", null, status);
        if (withArtifact) {
            v.sealWith("a".repeat(64));
        }
        when(toolRepository.findByName(TOOL)).thenReturn(Optional.of(tool));
        when(versionRepository.findByToolNameAndVersion(TOOL, raw)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    @DisplayName("DRAFT -> PUBLISHED is the release gate")
    void draftCanBePublished() {
        existing("1.2", VersionStatus.DRAFT, true);

        assertThat(service.promote(TOOL, "1.2", VersionStatus.PUBLISHED).getStatus())
                .isEqualTo(VersionStatus.PUBLISHED);
    }

    @Test
    @DisplayName("a version with no artifact cannot be published")
    void cannotPublishWithoutBytes() {
        existing("1.2", VersionStatus.DRAFT, false);

        assertThatThrownBy(() -> service.promote(TOOL, "1.2", VersionStatus.PUBLISHED))
                .isInstanceOf(IllegalPromotionException.class)
                .hasMessageContaining("no artifact has been uploaded");

        verify(versionRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUBLISHED -> DEPRECATED -> PUBLISHED is allowed (un-deprecate)")
    void deprecationIsReversible() {
        ToolVersion v = existing("1.2", VersionStatus.PUBLISHED, true);

        assertThat(service.promote(TOOL, "1.2", VersionStatus.DEPRECATED).getStatus())
                .isEqualTo(VersionStatus.DEPRECATED);
        assertThat(service.promote(TOOL, "1.2", VersionStatus.PUBLISHED).getStatus())
                .isEqualTo(VersionStatus.PUBLISHED);
        assertThat(v.getChecksumSha256()).as("bytes never changed").isEqualTo("a".repeat(64));
    }

    @Test
    @DisplayName("REVOKED is terminal - nothing comes back from it")
    void revokedIsTerminal() {
        existing("1.2", VersionStatus.REVOKED, true);

        assertThatThrownBy(() -> service.promote(TOOL, "1.2", VersionStatus.PUBLISHED))
                .isInstanceOf(IllegalPromotionException.class);
    }

    @Test
    @DisplayName("DRAFT -> DEPRECATED skips the lifecycle and is rejected")
    void cannotSkipStages() {
        existing("1.2", VersionStatus.DRAFT, true);

        assertThatThrownBy(() -> service.promote(TOOL, "1.2", VersionStatus.DEPRECATED))
                .isInstanceOf(IllegalPromotionException.class)
                .hasMessageContaining("allowed from DRAFT");
    }

    @Test
    @DisplayName("promoting to the current status is a no-op, so pipeline retries are safe")
    void promotionIsIdempotent() {
        existing("1.2", VersionStatus.PUBLISHED, true);

        assertThat(service.promote(TOOL, "1.2", VersionStatus.PUBLISHED).getStatus())
                .isEqualTo(VersionStatus.PUBLISHED);
        verify(versionRepository, never()).save(any());
    }
}
