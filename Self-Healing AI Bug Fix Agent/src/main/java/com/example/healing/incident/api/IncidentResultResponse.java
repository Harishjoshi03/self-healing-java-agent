package com.example.healing.incident.api;

import com.example.healing.incident.domain.IncidentStatus;
import java.util.UUID;

public record IncidentResultResponse(UUID incidentId, IncidentStatus status, String diagnosis,
                                     String evidence, String attempts, String changedFiles,
                                     String testResults, String pullRequestUrl, String escalationReason) {
}