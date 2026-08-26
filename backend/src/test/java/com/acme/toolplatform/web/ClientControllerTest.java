package com.acme.toolplatform.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.toolplatform.domain.Client;
import com.acme.toolplatform.domain.ClientToolConfiguration;
import com.acme.toolplatform.domain.SemanticVersion;
import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionSelector;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.service.ClientConfigurationService;
import com.acme.toolplatform.service.VersionResolution;
import com.acme.toolplatform.service.exception.ResourceNotFoundException;
import com.acme.toolplatform.service.exception.VersionRevokedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ClientController.class)
class ClientControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ClientConfigurationService clients;

    private ToolVersion version(String raw, VersionStatus status) {
        return new ToolVersion(new Tool("data-validator", null), SemanticVersion.parse(raw),
                "data-validator/" + raw + "/data-validator-" + raw + ".jar", null, status);
    }

    @Test
    @DisplayName("PUT pins a client to an exact version")
    void putPinsVersion() throws Exception {
        ClientToolConfiguration config =
                new ClientToolConfiguration(new Client("client-a", null), new Tool("data-validator", null));
        config.pinTo(version("1.0", VersionStatus.PUBLISHED));
        when(clients.setVersion("client-a", "data-validator", "1.0")).thenReturn(config);

        mockMvc.perform(put("/api/v1/clients/client-a/tools/data-validator/version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"version":"1.0"}
                                 """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selector").value("PINNED"))
                .andExpect(jsonPath("$.pinnedVersion").value("1.0"));
    }

    @Test
    @DisplayName("a nonsense version is rejected at the edge with 422")
    void rejectsNonsenseVersion() throws Exception {
        mockMvc.perform(put("/api/v1/clients/client-a/tools/data-validator/version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"version":"newest-please"}
                                 """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    @DisplayName("the resolve endpoint reports WHICH version and WHY")
    void resolveReportsSelector() throws Exception {
        when(clients.resolveForClient("client-c", "data-validator"))
                .thenReturn(new VersionResolution(VersionSelector.LATEST, version("2.0", VersionStatus.PUBLISHED)));

        mockMvc.perform(get("/api/v1/clients/client-c/tools/data-validator/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selector").value("LATEST"))
                .andExpect(jsonPath("$.resolvedVersion").value("2.0"))
                .andExpect(jsonPath("$.deprecated").value(false))
                .andExpect(header().doesNotExist("Deprecation"));
    }

    @Test
    @DisplayName("a DEPRECATED version resolves 200 but carries a Deprecation header")
    void deprecatedAddsHeader() throws Exception {
        when(clients.resolveForClient("client-a", "data-validator"))
                .thenReturn(new VersionResolution(VersionSelector.PINNED, version("1.0", VersionStatus.DEPRECATED)));

        mockMvc.perform(get("/api/v1/clients/client-a/tools/data-validator/version"))
                .andExpect(status().isOk())
                .andExpect(header().string("Deprecation", "true"))
                .andExpect(jsonPath("$.deprecated").value(true));
    }

    @Test
    @DisplayName("a REVOKED version is 410 Gone, not 404")
    void revokedIsGone() throws Exception {
        when(clients.resolveForClient(anyString(), anyString()))
                .thenThrow(new VersionRevokedException("has been REVOKED"));

        mockMvc.perform(get("/api/v1/clients/client-a/tools/data-validator/version"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("https://platform.acme.internal/errors/version-revoked"));
    }

    @Test
    @DisplayName("an unconfigured client gets a 404 that says how to fix it")
    void unconfiguredIs404WithGuidance() throws Exception {
        when(clients.resolveForClient(anyString(), anyString()))
                .thenThrow(new ResourceNotFoundException(
                        "Client 'client-z' has no configuration for tool 'data-validator'; set one with PUT ..."));

        mockMvc.perform(get("/api/v1/clients/client-z/tools/data-validator/version"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("set one with PUT")));
    }

    @Test
    @DisplayName("DELETE removes the configuration and returns 204")
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/clients/client-a/tools/data-validator/version"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("deleting a configuration that is not there is 404")
    void deleteMissingIs404() throws Exception {
        doThrow(new ResourceNotFoundException("no configuration"))
                .when(clients).removeConfiguration("client-a", "ghost-tool");

        mockMvc.perform(delete("/api/v1/clients/client-a/tools/ghost-tool/version"))
                .andExpect(status().isNotFound());
    }
}
