package com.example.healing.audit.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(nullable = false, length = 40)
    private String actorType;

    @Column(nullable = false, length = 120)
    private String action;

    @Column(name = "summary", nullable = false, length = 1000)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(UUID incidentId, String actorType, String action, String summary) {
        this.incidentId = incidentId;
        this.actorType = actorType;
        this.action = action;
        this.summary = summary;
        this.createdAt = Instant.now();
    }
}