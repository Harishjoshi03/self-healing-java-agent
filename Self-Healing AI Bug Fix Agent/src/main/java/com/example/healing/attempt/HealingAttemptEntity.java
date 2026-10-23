package com.example.healing.attempt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "healing_attempts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_attempts_incident_number", columnNames = {"incident_id", "attempt_number"})
})
public class HealingAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "patch_summary", length = 1000)
    private String patchSummary;

    @Column(name = "changed_files_json", columnDefinition = "text")
    private String changedFilesJson;

    @Column(name = "command", length = 1000)
    private String command;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "duration_millis")
    private Long durationMillis;

    @Column(name = "output", columnDefinition = "text")
    private String output;

    @Column(name = "timed_out", nullable = false)
    private boolean timedOut;

    @Column(name = "sandboxed", nullable = false)
    private boolean sandboxed;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HealingAttemptEntity() {
    }

    public HealingAttemptEntity(UUID incidentId, int attemptNumber, String patchSummary, String changedFilesJson,
                                String command, Integer exitCode, long durationMillis,
                                String output, boolean timedOut, boolean sandboxed, boolean passed) {
        this.incidentId = incidentId;
        this.attemptNumber = attemptNumber;
        this.patchSummary = patchSummary;
        this.changedFilesJson = changedFilesJson;
        this.command = command;
        this.exitCode = exitCode;
        this.durationMillis = durationMillis;
        this.output = output;
        this.timedOut = timedOut;
        this.sandboxed = sandboxed;
        this.passed = passed;
        this.createdAt = Instant.now();
    }

    public int getAttemptNumber() { return attemptNumber; }
    public Integer getExitCode() { return exitCode; }
    public boolean isPassed() { return passed; }
}