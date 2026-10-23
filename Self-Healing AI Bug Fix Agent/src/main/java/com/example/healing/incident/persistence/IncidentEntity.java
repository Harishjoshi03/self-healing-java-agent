package com.example.healing.incident.persistence;

import com.example.healing.incident.domain.IncidentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents", uniqueConstraints = {
        @UniqueConstraint(name = "uk_incidents_idempotency_key", columnNames = "idempotency_key")
})
public class IncidentEntity {

    @Id
    private UUID id;

    @Column(name = "service_name", nullable = false, length = 120)
    private String serviceName;

    @Column(name = "repository_identifier", nullable = false, length = 180)
    private String repository;

    @Column(name = "branch_name", nullable = false, length = 120)
    private String branch;

    @Column(name = "error_message", nullable = false, length = 4000)
    private String errorMessage;

    @Column(name = "sanitized_stack_trace", nullable = false, columnDefinition = "text")
    private String stackTrace;

    @Column(name = "environment_name", length = 80)
    private String environment;

    @Column(name = "correlation_id", length = 120)
    private String correlationId;

    @Column(name = "idempotency_key", length = 120)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IncidentStatus status;

    @Column(length = 1000)
    private String stage;

    @Column(name = "diagnosis_json", columnDefinition = "text")
    private String diagnosisJson;

    @Column(name = "evidence_json", columnDefinition = "text")
    private String evidenceJson;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(name = "changed_files_json", columnDefinition = "text")
    private String changedFilesJson;

    @Column(name = "pull_request_url", length = 1000)
    private String pullRequestUrl;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "escalation_reason", length = 2000)
    private String escalationReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IncidentEntity() {
    }

    public IncidentEntity(String serviceName, String repository, String branch, String errorMessage,
                          String stackTrace, String environment, String correlationId, String idempotencyKey) {
        this.id = UUID.randomUUID();
        this.serviceName = serviceName;
        this.repository = repository;
        this.branch = branch;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
        this.environment = environment;
        this.correlationId = correlationId;
        this.idempotencyKey = idempotencyKey;
        this.status = IncidentStatus.RECEIVED;
        this.stage = "Incident received";
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void updateStatus(IncidentStatus status, String stage, String escalationReason) {
        if (!this.status.allowsTransitionTo(status)) {
            throw new IllegalStateException("Illegal incident status transition: " + this.status + " -> " + status);
        }
        this.status = status;
        this.stage = stage;
        this.escalationReason = escalationReason;
    }

    public void recordDiagnosis(String diagnosisJson, String evidenceJson, IncidentStatus status,
                                String stage, String escalationReason) {
        this.diagnosisJson = diagnosisJson;
        this.evidenceJson = evidenceJson;
        updateStatus(status, stage, escalationReason);
    }

    public void recordAttempt(String resultJson, String changedFilesJson, int attemptCount) {
        this.resultJson = resultJson;
        this.changedFilesJson = changedFilesJson;
        this.attemptCount = attemptCount;
    }

    public void recordPullRequest(String pullRequestUrl) {
        if (status != IncidentStatus.VALIDATED) {
            throw new IllegalStateException("A pull request requires a validated incident");
        }
        this.pullRequestUrl = pullRequestUrl;
        updateStatus(IncidentStatus.PR_CREATED, "Pull request created; waiting for human review", null);
    }

    public UUID getId() { return id; }
    public String getServiceName() { return serviceName; }
    public String getRepository() { return repository; }
    public String getBranch() { return branch; }
    public String getErrorMessage() { return errorMessage; }
    public String getStackTrace() { return stackTrace; }
    public String getEnvironment() { return environment; }
    public String getCorrelationId() { return correlationId; }
    public IncidentStatus getStatus() { return status; }
    public String getStage() { return stage; }
    public String getDiagnosisJson() { return diagnosisJson; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getResultJson() { return resultJson; }
    public String getChangedFilesJson() { return changedFilesJson; }
    public String getPullRequestUrl() { return pullRequestUrl; }
    public int getAttemptCount() { return attemptCount; }
    public String getEscalationReason() { return escalationReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}