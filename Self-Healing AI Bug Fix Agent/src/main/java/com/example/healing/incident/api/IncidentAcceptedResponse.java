package com.example.healing.incident.api;

import com.example.healing.incident.domain.IncidentStatus;
import java.util.UUID;

public record IncidentAcceptedResponse(UUID incidentId, IncidentStatus status, String statusUrl) {
}