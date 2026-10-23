package com.example.healing.incident.api;

import com.example.healing.incident.application.IncidentService;
import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.attempt.HealingAttemptService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/incidents")
@Tag(name = "Incidents", description = "Submit incidents and inspect their healing workflow results")
@SecurityRequirement(name = "basicAuth")
public class IncidentController {

    private final IncidentService incidentService;
    private final HealingAttemptService attempts;
    private final ObjectMapper objectMapper;

    public IncidentController(IncidentService incidentService, HealingAttemptService attempts,
                              ObjectMapper objectMapper) {
        this.incidentService = incidentService;
        this.attempts = attempts;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    @Operation(summary = "Submit an incident", description = "Stores a sanitized incident and returns its tracking ID.")
        @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Incident accepted"),
            @ApiResponse(responseCode = "400", description = "Request validation failed"),
            @ApiResponse(responseCode = "413", description = "Request exceeds the 64 KiB limit"),
            @ApiResponse(responseCode = "401", description = "HTTP Basic credentials are missing or invalid")
        })
    public ResponseEntity<IncidentAcceptedResponse> submit(
            @Valid @RequestBody IncidentRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        IncidentEntity incident = incidentService.submit(request, idempotencyKey);
        String statusUrl = "/api/incidents/" + incident.getId();
        return ResponseEntity.accepted().location(URI.create(statusUrl))
                .body(new IncidentAcceptedResponse(incident.getId(), incident.getStatus(), statusUrl));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get incident status")
        @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current workflow state"),
            @ApiResponse(responseCode = "404", description = "Incident not found"),
            @ApiResponse(responseCode = "401", description = "HTTP Basic credentials are missing or invalid")
        })
    public IncidentStatusResponse status(@PathVariable UUID id) {
        IncidentEntity incident = incidentService.get(id);
        return new IncidentStatusResponse(incident.getId(), incident.getStatus(), incident.getStage(),
                incident.getCreatedAt(), incident.getUpdatedAt());
    }

    @GetMapping("/{id}/result")
    @Operation(summary = "Get incident diagnosis and validation result")
        @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Diagnosis, evidence, attempts, and actual validation results"),
            @ApiResponse(responseCode = "404", description = "Incident not found"),
            @ApiResponse(responseCode = "401", description = "HTTP Basic credentials are missing or invalid")
        })
    public IncidentResultResponse result(@PathVariable UUID id) {
        IncidentEntity incident = incidentService.get(id);
        try {
            String attemptJson = objectMapper.writeValueAsString(attempts.list(incident));
            return new IncidentResultResponse(incident.getId(), incident.getStatus(), incident.getDiagnosisJson(),
                    incident.getEvidenceJson(), attemptJson, incident.getChangedFilesJson(), incident.getResultJson(),
                    incident.getPullRequestUrl(), incident.getEscalationReason());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize attempt results", exception);
        }
    }
}