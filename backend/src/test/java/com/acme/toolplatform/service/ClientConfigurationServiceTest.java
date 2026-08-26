package com.acme.toolplatform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.acme.toolplatform.domain.Client;
import com.acme.toolplatform.domain.ClientToolConfiguration;
import com.acme.toolplatform.domain.SemanticVersion;
import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionSelector;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.repository.ClientRepository;
import com.acme.toolplatform.repository.ClientToolConfigurationRepository;
import com.acme.toolplatform.service.exception.ResourceNotFoundException;
import com.acme.toolplatform.service.exception.VersionRevokedException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClientConfigurationServiceTest {

    private static final String CLIENT = "client-a";
    private static final String TOOL = "data-validator";

    @Mock ClientRepository clientRepository;
    @Mock ClientToolConfigurationRepository configRepository;
    @Mock ToolRegistryService registry;

    ClientConfigurationService service;
    Client client;
    Tool tool;

    @BeforeEach
    void setUp() {
        service = new ClientConfigurationService(clientRepository, configRepository, registry);
        client = new Client(CLIENT, "Audit-frozen consumer");
        tool = new Tool(TOOL, null);
    }

    private ToolVersion version(String raw, VersionStatus status) {
        return new ToolVersion(tool, SemanticVersion.parse(raw),
                "data-validator/" + raw + "/data-validator-" + raw + ".jar", null, status);
    }

    private void clientAndToolExist() {
        when(clientRepository.findByName(CLIENT)).thenReturn(Optional.of(client));
        when(registry.getTool(TOOL)).thenReturn(tool);
    }

    @Test
    @DisplayName("a concrete version pins the client to exactly that version")
    void pinsToExactVersion() {
        clientAndToolExist();
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.empty());
        when(registry.resolveExactVersion(TOOL, "1.2")).thenReturn(version("1.2", VersionStatus.PUBLISHED));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClientToolConfiguration config = service.setVersion(CLIENT, TOOL, "1.2");

        assertThat(config.getSelector()).isEqualTo(VersionSelector.PINNED);
        assertThat(config.getPinnedVersion().getVersion()).isEqualTo("1.2");
    }

    @Test
    @DisplayName("'latest' is accepted here as an explicit opt-in and stores no pinned version")
    void latestIsAnExplicitOptIn() {
        clientAndToolExist();
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.empty());
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClientToolConfiguration config = service.setVersion(CLIENT, TOOL, "LATEST");

        assertThat(config.getSelector()).isEqualTo(VersionSelector.LATEST);
        assertThat(config.getPinnedVersion()).isNull();
        verify(registry, never()).resolveExactVersion(anyString(), anyString());
    }

    @Test
    @DisplayName("pinning to a version that does not exist fails at configuration time")
    void cannotPinToMissingVersion() {
        clientAndToolExist();
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.empty());
        when(registry.resolveExactVersion(TOOL, "999.0"))
                .thenThrow(new ResourceNotFoundException("Version '999.0' ... does not exist"));

        assertThatThrownBy(() -> service.setVersion(CLIENT, TOOL, "999.0"))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(configRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unconfigured client is a 404, not a silent default to latest")
    void unconfiguredClientIsNotFound() {
        clientAndToolExist();
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveForClient(CLIENT, TOOL))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("has no configuration");

        verify(registry, never()).findLatestVersion(anyString());
    }

    @Test
    @DisplayName("a PINNED client never consults 'latest', even when newer versions exist")
    void pinnedClientIgnoresNewerVersions() {
        clientAndToolExist();
        ClientToolConfiguration config = new ClientToolConfiguration(client, tool);
        config.pinTo(version("1.0", VersionStatus.PUBLISHED));
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.of(config));

        VersionResolution resolution = service.resolveForClient(CLIENT, TOOL);

        assertThat(resolution.selector()).isEqualTo(VersionSelector.PINNED);
        assertThat(resolution.version().getVersion()).isEqualTo("1.0");
        verify(registry, never()).findLatestVersion(anyString());
    }

    @Test
    @DisplayName("a LATEST client resolves to the newest published version")
    void latestClientFollowsNewest() {
        clientAndToolExist();
        ClientToolConfiguration config = new ClientToolConfiguration(client, tool);
        config.followLatest();
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.of(config));
        when(registry.findLatestVersion(TOOL)).thenReturn(Optional.of(version("2.0", VersionStatus.PUBLISHED)));

        VersionResolution resolution = service.resolveForClient(CLIENT, TOOL);

        assertThat(resolution.selector()).isEqualTo(VersionSelector.LATEST);
        assertThat(resolution.version().getVersion()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("resolving a REVOKED version fails loudly instead of serving withdrawn bytes")
    void revokedVersionIsRefused() {
        clientAndToolExist();
        ClientToolConfiguration config = new ClientToolConfiguration(client, tool);
        config.pinTo(version("1.1", VersionStatus.REVOKED));
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.of(config));

        assertThatThrownBy(() -> service.resolveForClient(CLIENT, TOOL))
                .isInstanceOf(VersionRevokedException.class);
    }

    @Test
    @DisplayName("a DEPRECATED version still resolves, but is flagged")
    void deprecatedVersionResolvesWithFlag() {
        clientAndToolExist();
        ClientToolConfiguration config = new ClientToolConfiguration(client, tool);
        config.pinTo(version("1.0", VersionStatus.DEPRECATED));
        when(configRepository.findByClientNameAndToolName(CLIENT, TOOL)).thenReturn(Optional.of(config));

        VersionResolution resolution = service.resolveForClient(CLIENT, TOOL);

        assertThat(resolution.isDeprecated()).isTrue();
        assertThat(resolution.version().getVersion()).isEqualTo("1.0");
    }
}
