package com.acme.toolplatform.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acme.toolplatform.domain.SemanticVersion;
import com.acme.toolplatform.domain.Tool;
import com.acme.toolplatform.domain.ToolVersion;
import com.acme.toolplatform.domain.VersionStatus;
import com.acme.toolplatform.service.ToolRegistryService;
import com.acme.toolplatform.service.exception.DuplicateResourceException;
import com.acme.toolplatform.service.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.acme.toolplatform.security.SecurityConfig;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web SLICE test: only the MVC layer is started, the service is mocked.
 * It verifies the HTTP contract - status codes, headers, JSON shape,
 * problem+json errors - without a database. Still the fast lane.
 */
@Import(SecurityConfig.class)
@WebMvcTest({ToolController.class, ToolVersionController.class})
class ToolControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean ToolRegistryService registry;

    @Test
    @DisplayName("POST /api/v1/tools returns 201 with a Location header")
    void createsTool() throws Exception {
        when(registry.registerTool(anyString(), any()))
                .thenReturn(new Tool("data-validator", "Validates inbound data files"));

        mockMvc.perform(post("/api/v1/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"name":"data-validator","description":"Validates inbound data files"}
                                 """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/tools/data-validator"))
                .andExpect(jsonPath("$.name").value("data-validator"));
    }

    @Test
    @DisplayName("an invalid tool name is rejected at the edge with 422 + field errors")
    void rejectsInvalidToolName() throws Exception {
        mockMvc.perform(post("/api/v1/tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"name":"../../etc/passwd"}
                                 """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://platform.acme.internal/errors/validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    @DisplayName("a missing version returns 404 as problem+json, never a fallback version")
    void missingVersionReturnsProblemJson() throws Exception {
        when(registry.resolveExactVersion("data-validator", "999.0"))
                .thenThrow(new ResourceNotFoundException("Version '999.0' of tool 'data-validator' does not exist"));

        mockMvc.perform(get("/api/v1/tools/data-validator/versions/999.0"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://platform.acme.internal/errors/not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/tools/data-validator/versions/999.0"));
    }

    @Test
    @DisplayName("the dotted version in the path is NOT truncated")
    void keepsDottedVersionIntact() throws Exception {
        ToolVersion v = new ToolVersion(new Tool("data-validator", null), SemanticVersion.parse("1.2"),
                "data-validator/1.2/data-validator-1.2.jar",
                "a".repeat(64), VersionStatus.PUBLISHED);
        when(registry.resolveExactVersion("data-validator", "1.2")).thenReturn(v);

        mockMvc.perform(get("/api/v1/tools/data-validator/versions/1.2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.2"))
                .andExpect(jsonPath("$.tool").value("data-validator"));
    }

    @Test
    @DisplayName("re-publishing an existing version is 409 Conflict")
    void duplicateVersionIsConflict() throws Exception {
        when(registry.publishVersion(anyString(), anyString(), anyString(), any(), any()))
                .thenThrow(new DuplicateResourceException("already exists and is immutable"));

        mockMvc.perform(post("/api/v1/tools/data-validator/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {"version":"1.2","artifactPath":"data-validator/1.2/data-validator-1.2.jar"}
                                 """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://platform.acme.internal/errors/duplicate-resource"));
    }
}
