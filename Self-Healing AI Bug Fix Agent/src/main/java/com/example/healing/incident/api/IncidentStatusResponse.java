package com.example.healing.incident.api;

import com.example.healing.incident.domain.IncidentStatus;
import java.time.Instant;
import java.util.UUID;

public record IncidentStatusResponse(UUID incidentId, IncidentStatus status, String stage,
                                     Instant createdAt, Instant updatedAt) {
}