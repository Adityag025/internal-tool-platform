package com.acme.toolplatform.web;

import com.acme.toolplatform.domain.Client;
import com.acme.toolplatform.domain.ClientToolConfiguration;
import com.acme.toolplatform.service.ArtifactDownload;
import com.acme.toolplatform.service.ArtifactService;
import com.acme.toolplatform.service.ClientConfigurationService;
import com.acme.toolplatform.service.VersionResolution;
import com.acme.toolplatform.web.dto.ClientConfigurationResponse;
import com.acme.toolplatform.web.dto.ClientResponse;
import com.acme.toolplatform.web.dto.CreateClientRequest;
import com.acme.toolplatform.web.dto.PageResponse;
import com.acme.toolplatform.web.dto.ResolvedVersionResponse;
import com.acme.toolplatform.web.dto.SetVersionRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/clients")
public class ClientController {

    private final ClientConfigurationService clients;
    private final ArtifactService artifacts;

    public ClientController(ClientConfigurationService clients, ArtifactService artifacts) {
        this.clients = clients;
        this.artifacts = artifacts;
    }

    @PostMapping
    public ResponseEntity<ClientResponse> createClient(@Valid @RequestBody CreateClientRequest request) {
        Client client = clients.registerClient(request.name(), request.description());
        return ResponseEntity
                .created(URI.create("/api/v1/clients/" + client.getName()))
                .body(ClientResponse.from(client));
    }

    @GetMapping
    public PageResponse<ClientResponse> listClients(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
                clients.listClients(PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100), Sort.by("name"))),
                ClientResponse::from);
    }

    @GetMapping("/{clientName}")
    public ClientResponse getClient(@PathVariable String clientName) {
        return ClientResponse.from(clients.getClient(clientName));
    }

    /** Everything this client is configured to consume. */
    @GetMapping("/{clientName}/tools")
    public List<ClientConfigurationResponse> listConfigurations(@PathVariable String clientName) {
        return clients.listConfigurations(clientName).stream()
                .map(ClientConfigurationResponse::from)
                .toList();
    }

    /**
     * PUT, not POST.
     *
     * A client has exactly ONE version decision per tool, and that decision
     * lives at a known URL. PUT is the correct verb for "make the state at
     * this URL be exactly this" - it is idempotent, so a retried deployment
     * or a re-applied config file cannot create duplicates. POST would imply
     * appending a new subordinate resource each time, which is not what
     * happens here.
     *
     * This is also the ROLLBACK operation: moving client-c from 2.0 back to
     * 1.2 is this single call. No rebuild, no artifact change.
     */
    @PutMapping("/{clientName}/tools/{toolName}/version")
    public ClientConfigurationResponse setVersion(
            @PathVariable String clientName,
            @PathVariable String toolName,
            @Valid @RequestBody SetVersionRequest request) {

        ClientToolConfiguration config = clients.setVersion(clientName, toolName, request.version());
        return ClientConfigurationResponse.from(toolName, config);
    }

    /** The runtime question: what does this client get right now, and why? */
    @GetMapping("/{clientName}/tools/{toolName}/version")
    public ResponseEntity<ResolvedVersionResponse> resolveVersion(
            @PathVariable String clientName,
            @PathVariable String toolName) {

        VersionResolution resolution = clients.resolveForClient(clientName, toolName);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();

        // RFC 8594-style signal. A machine consumer can alert on the header
        // without having to parse and understand the body.
        if (resolution.isDeprecated()) {
            response.header("Deprecation", "true");
        }
        return response.body(ResolvedVersionResponse.from(clientName, toolName, resolution));
    }

    /**
     * The whole point of the platform, in one call.
     *
     * "Give me my copy of data-validator" - the client names no version at
     * all. The platform resolves its configuration to one exact version,
     * fetches those exact bytes, verifies the checksum, and streams them back.
     * Consumers stay decoupled from version numbers; the pin is the contract.
     */
    @GetMapping("/{clientName}/tools/{toolName}/artifact")
    public ResponseEntity<Resource> downloadForClient(
            @PathVariable String clientName,
            @PathVariable String toolName) {

        ArtifactDownload download = artifacts.downloadForClient(clientName, toolName);
        return ArtifactController.artifactResponse(download);
    }

    @DeleteMapping("/{clientName}/tools/{toolName}/version")
    public ResponseEntity<Void> removeConfiguration(
            @PathVariable String clientName,
            @PathVariable String toolName) {
        clients.removeConfiguration(clientName, toolName);
        return ResponseEntity.noContent().build();
    }
}
