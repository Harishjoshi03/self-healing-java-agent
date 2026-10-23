package com.example.healing.incident.application;

import com.example.healing.audit.persistence.AuditEventEntity;
import com.example.healing.audit.persistence.AuditEventRepository;
import com.example.healing.incident.api.IncidentRequest;
import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.incident.persistence.IncidentRepository;
import com.example.healing.orchestration.IncidentSubmittedEvent;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final AuditEventRepository auditEvents;
    private final SecretRedactor redactor;
    private final ApplicationEventPublisher events;

    public IncidentService(IncidentRepository incidents, AuditEventRepository auditEvents, SecretRedactor redactor,
                           ApplicationEventPublisher events) {
        this.incidents = incidents;
        this.auditEvents = auditEvents;
        this.redactor = redactor;
        this.events = events;
    }

    @Transactional
    public IncidentEntity submit(IncidentRequest request, String idempotencyKey) {
        if (idempotencyKey != null && (idempotencyKey.length() > 120
                || !idempotencyKey.matches("[A-Za-z0-9._:-]+"))) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-120 safe ASCII characters");
        }
        if (idempotencyKey != null) {
            var existing = incidents.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        IncidentEntity incident = incidents.save(new IncidentEntity(
                request.serviceName().trim(), request.repository().trim(), request.branch().trim(),
                redactor.redact(request.errorMessage()), redactor.redact(request.stackTrace()),
                request.environment(), request.correlationId(), idempotencyKey));
        auditEvents.save(new AuditEventEntity(incident.getId(), "USER", "INCIDENT_SUBMITTED",
                "Sanitized incident submitted for " + incident.getRepository()));
        events.publishEvent(new IncidentSubmittedEvent(incident.getId()));
        return incident;
    }

    @Transactional(readOnly = true)
    public IncidentEntity get(UUID id) {
        return incidents.findById(id).orElseThrow(IncidentNotFoundException::new);
    }
}