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
import com.acme.toolplatform.service.exception.DuplicateResourceException;
import com.acme.toolplatform.service.exception.InvalidVersionException;
import com.acme.toolplatform.service.exception.ResourceNotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test of the business rules with mocked repositories.
 * No Spring context is started - this is deliberately fast.
 */
@ExtendWith(MockitoExtension.class)
class ToolRegistryServiceTest {

    private static final String TOOL = "data-validator";

    @Mock ToolRepository toolRepository;
    @Mock ToolVersionRepository versionRepository;

    ToolRegistryService service;
    Tool tool;

    @BeforeEach
    void setUp() {
        service = new ToolRegistryService(toolRepository, versionRepository);
        tool = new Tool(TOOL, "Validates inbound data files");
    }

    @Test
    @DisplayName("registering an existing tool is a 409, not a silent overwrite")
    void rejectsDuplicateTool() {
        when(toolRepository.existsByName(TOOL)).thenReturn(true);

        assertThatThrownBy(() -> service.registerTool(TOOL, "anything"))
                .isInstanceOf(DuplicateResourceException.class);

        verify(toolRepository, never()).save(any());
    }

    @Test
    @DisplayName("publishing a version that already exists is rejected - artifacts are immutable")
    void rejectsDuplicateVersion() {
        when(toolRepository.findByName(TOOL)).thenReturn(Optional.of(tool));
        when(versionRepository.existsByToolNameAndVersion(TOOL, "1.2")).thenReturn(true);

        assertThatThrownBy(() -> service.publishVersion(TOOL, "1.2", "path/x.jar", null, null))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("immutable");

        verify(versionRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a malformed version never reaches the database")
    void rejectsMalformedVersion() {
        when(toolRepository.findByName(TOOL)).thenReturn(Optional.of(tool));

        assertThatThrownBy(() -> service.publishVersion(TOOL, "latest", "path/x.jar", null, null))
                .isInstanceOf(InvalidVersionException.class);
    }

    @Test
    @DisplayName("an unknown tool is a 404")
    void unknownToolIsNotFound() {
        when(toolRepository.findByName("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTool("ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("a missing version 404s instead of silently returning latest")
    void missingVersionDoesNotFallBackToLatest() {
        when(toolRepository.findByName(TOOL)).thenReturn(Optional.of(tool));
        when(versionRepository.findByToolNameAndVersion(TOOL, "999.0")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveExactVersion(TOOL, "999.0"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("an existing version resolves to exactly the requested version")
    void resolvesExactVersion() {
        ToolVersion v = new ToolVersion(tool, SemanticVersion.parse("1.2"),
                "data-validator/1.2/data-validator-1.2.jar", null, VersionStatus.PUBLISHED);
        when(toolRepository.findByName(TOOL)).thenReturn(Optional.of(tool));
        when(versionRepository.findByToolNameAndVersion(TOOL, "1.2")).thenReturn(Optional.of(v));

        assertThat(service.resolveExactVersion(TOOL, "1.2").getVersion()).isEqualTo("1.2");
    }
}
