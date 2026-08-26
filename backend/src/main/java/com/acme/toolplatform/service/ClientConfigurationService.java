package com.acme.toolplatform.service;

import com.acme.toolplatform.domain.Client;
import com.acme.toolplatform.domain.ClientToolConfiguration;
import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionSelector;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.repository.ClientRepository;
import com.acme.toolplatform.repository.ClientToolConfigurationRepository;
import com.acme.toolplatform.service.exception.DuplicateResourceException;
import com.acme.toolplatform.service.exception.ResourceNotFoundException;
import com.acme.toolplatform.service.exception.VersionRevokedException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns "which version does client X get for tool Y?".
 *
 * The registry (Phase 1) answers what EXISTS. This service answers what a
 * given consumer USES - and the two are deliberately separate concerns.
 */
@Service
public class ClientConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(ClientConfigurationService.class);

    /** The only string that opts a client out of pinning. */
    public static final String LATEST_KEYWORD = "latest";

    private final ClientRepository clientRepository;
    private final ClientToolConfigurationRepository configRepository;
    private final ToolRegistryService registry;

    public ClientConfigurationService(ClientRepository clientRepository,
                                      ClientToolConfigurationRepository configRepository,
                                      ToolRegistryService registry) {
        this.clientRepository = clientRepository;
        this.configRepository = configRepository;
        this.registry = registry;
    }

    // ---------------------------------------------------------------- clients

    @Transactional
    public Client registerClient(String name, String description) {
        if (clientRepository.existsByName(name)) {
            throw new DuplicateResourceException("Client '" + name + "' is already registered");
        }
        Client saved = clientRepository.save(new Client(name, description));
        log.info("client.registered name={} id={}", saved.getName(), saved.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<Client> listClients(Pageable pageable) {
        return clientRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Client getClient(String clientName) {
        return clientRepository.findByName(clientName)
                .orElseThrow(() -> new ResourceNotFoundException("Client '" + clientName + "' is not registered"));
    }

    // --------------------------------------------------------- configuration

    /**
     * Set (or change) which version a client gets. Idempotent upsert.
     *
     * Pass a concrete version to pin, or the literal "latest" to opt in to
     * floating. Pinning to a version that does not exist fails HERE, at
     * configuration time, with a 404 - not later, at download time, in
     * someone's deploy. Push validation as early as it will go.
     */
    @Transactional
    public ClientToolConfiguration setVersion(String clientName, String toolName, String requestedVersion) {
        Client client = getClient(clientName);
        Tool tool = registry.getTool(toolName);

        ClientToolConfiguration config = configRepository
                .findByClientNameAndToolName(clientName, toolName)
                .orElseGet(() -> new ClientToolConfiguration(client, tool));

        String previous = describe(config);

        if (LATEST_KEYWORD.equalsIgnoreCase(requestedVersion.trim())) {
            config.followLatest();
        } else {
            // Reuses the Phase 1 exact-resolution rules: malformed -> 400,
            // unknown -> 404. One implementation, one behaviour, everywhere.
            ToolVersion version = registry.resolveExactVersion(toolName, requestedVersion);
            config.pinTo(version);
        }

        ClientToolConfiguration saved = configRepository.save(config);
        log.info("client.config.changed client={} tool={} from=[{}] to=[{}]",
                clientName, toolName, previous, describe(saved));
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ClientToolConfiguration> listConfigurations(String clientName) {
        getClient(clientName);
        return configRepository.findAllByClientNameFetchingTool(clientName);
    }

    @Transactional
    public void removeConfiguration(String clientName, String toolName) {
        getClient(clientName);
        if (configRepository.deleteByClientNameAndToolName(clientName, toolName) == 0) {
            throw new ResourceNotFoundException(
                    "Client '" + clientName + "' has no configuration for tool '" + toolName + "'");
        }
        log.info("client.config.removed client={} tool={}", clientName, toolName);
    }

    // ------------------------------------------------------------ resolution

    /**
     * THE runtime question: what does this client actually get right now?
     *
     * Note what does NOT happen: a client with no configuration gets a 404,
     * not a helpful default of "newest". An unconfigured consumer is a
     * mistake to surface, not a gap to paper over.
     */
    @Transactional(readOnly = true)
    public VersionResolution resolveForClient(String clientName, String toolName) {
        long startNanos = System.nanoTime();
        getClient(clientName);
        registry.getTool(toolName);

        ClientToolConfiguration config = configRepository
                .findByClientNameAndToolName(clientName, toolName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Client '" + clientName + "' has no configuration for tool '" + toolName
                                + "'; set one with PUT /api/v1/clients/" + clientName
                                + "/tools/" + toolName + "/version"));

        ToolVersion version = switch (config.getSelector()) {
            case PINNED -> config.getPinnedVersion();
            case LATEST -> registry.findLatestVersion(toolName)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Tool '" + toolName + "' has no published versions to resolve 'latest' to"));
        };

        if (version.getStatus() == VersionStatus.REVOKED) {
            throw new VersionRevokedException(
                    "Version '" + version.getVersion() + "' of tool '" + toolName
                            + "' has been REVOKED and must not be used; pin this client to another version");
        }

        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        // Even for LATEST the CONCRETE version is logged - so months later you
        // can still answer "which bytes did client-c actually get that day?".
        log.info("client.version.resolved client={} tool={} selector={} resolvedVersion={} status={} latencyMs={}",
                clientName, toolName, config.getSelector(), version.getVersion(), version.getStatus(), millis);

        return new VersionResolution(config.getSelector(), version);
    }

    private String describe(ClientToolConfiguration config) {
        if (config.getSelector() == null) {
            return "unconfigured";
        }
        return config.getSelector() == VersionSelector.LATEST
                ? "LATEST"
                : "PINNED:" + config.getPinnedVersion().getVersion();
    }
}
