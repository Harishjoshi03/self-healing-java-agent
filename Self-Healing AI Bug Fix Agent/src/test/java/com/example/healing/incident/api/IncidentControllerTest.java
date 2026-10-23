package com.example.healing.incident.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.healing.incident.persistence.IncidentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:incidents;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

        @Autowired
        private IncidentRepository incidentRepository;

        @Autowired
        private ObjectMapper objectMapper;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/incidents/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsIncidentAndRedactsBeforePersistenceAndResponse() throws Exception {
        String body = """
                {"serviceName":"orders","repository":"example/orders","branch":"main",
                 "errorMessage":"password=hunter2","stackTrace":"java.lang.IllegalStateException\\n password=hunter2",
                 "environment":"test"}
                """;

        MvcResult result = mockMvc.perform(post("/api/incidents")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("agent", "change-me-local-only"))
                        .header("Idempotency-Key", "test-incident-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        var incident = incidentRepository.findById(java.util.UUID.fromString(response.get("incidentId").asText()))
                .orElseThrow();
        assertThat(incident.getErrorMessage()).isEqualTo("password=[REDACTED]");
        assertThat(incident.getStackTrace()).doesNotContain("hunter2");
    }

    @Test
    void repeatedIdempotencyKeyReturnsSameIncident() throws Exception {
        String body = """
                {"serviceName":"orders","repository":"example/orders","branch":"main",
                 "errorMessage":"Failure","stackTrace":"java.lang.IllegalStateException"}
                """;
        String first = submit(body);
        String second = submit(body);

        assertThat(first).isEqualTo(second);
        assertThat(incidentRepository.count()).isEqualTo(1);
    }

    private String submit(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/incidents")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("agent", "change-me-local-only"))
                        .header("Idempotency-Key", "same-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("incidentId").asText();
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/incidents")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("agent", "change-me-local-only"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

        @Test
        void rejectsOversizedIncidentBodies() throws Exception {
                mockMvc.perform(post("/api/incidents")
                                                .with(SecurityMockMvcRequestPostProcessors.httpBasic("agent", "change-me-local-only"))
                                                .contentType(MediaType.APPLICATION_JSON).content("x".repeat(65 * 1024)))
                                .andExpect(status().isPayloadTooLarge());
        }

        @Test
        void healthEndpointDoesNotRequireAuthentication() throws Exception {
                mockMvc.perform(get("/actuator/health")).andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        void exposesOpenApiDocumentAndBasicAuthSchemeWithoutCredentials() throws Exception {
                mockMvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.info.title").value("Self-Healing Java Bug Fix Agent API"))
                                .andExpect(jsonPath("$.servers[0].url").value("/"))
                                .andExpect(jsonPath("$.paths['/api/incidents'].post").exists())
                                .andExpect(jsonPath("$.components.securitySchemes.basicAuth.scheme").value("basic"));
        }

        @Test
        void servesSwaggerUiWithoutCredentials() throws Exception {
                mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        }

        @Test
        void rootRedirectsToSwaggerUi() throws Exception {
                mockMvc.perform(get("/"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(header().string("Location", "/swagger-ui.html"));
        }
}