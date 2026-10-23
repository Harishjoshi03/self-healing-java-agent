package com.example.healing.orchestration;

import com.example.healing.audit.persistence.AuditEventEntity;
import com.example.healing.audit.persistence.AuditEventRepository;
import com.example.healing.attempt.HealingAttemptService;
import com.example.healing.diagnosis.DiagnosisProvider;
import com.example.healing.diagnosis.DiagnosisReport;
import com.example.healing.github.PullRequestResult;
import com.example.healing.github.PullRequestService;
import com.example.healing.incident.domain.IncidentStatus;
import com.example.healing.incident.persistence.IncidentEntity;
import com.example.healing.incident.persistence.IncidentRepository;
import com.example.healing.patch.IsolatedWorkspace;
import com.example.healing.patch.IsolatedWorkspaceFactory;
import com.example.healing.patch.PatchApplier;
import com.example.healing.patch.PatchPlan;
import com.example.healing.patch.PatchPlanner;
import com.example.healing.repository.RepositoryContext;
import com.example.healing.repository.RepositoryEvidenceCollector;
import com.example.healing.repository.RepositoryInspectionException;
import com.example.healing.sandbox.SandboxRunner;
import com.example.healing.sandbox.ValidationResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class IncidentWorkflow {

    private final IncidentRepository incidents;
    private final AuditEventRepository auditEvents;
    private final RepositoryEvidenceCollector evidenceCollector;
    private final IsolatedWorkspaceFactory workspaceFactory;
    private final PatchApplier patchApplier;
    private final HealingAttemptService attemptService;
    private final ObjectProvider<DiagnosisProvider> diagnosisProviders;
    private final ObjectProvider<PatchPlanner> patchPlanners;
    private final ObjectProvider<SandboxRunner> sandboxRunners;
    private final ObjectProvider<PullRequestService> pullRequestServices;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final boolean killSwitch;
    private final boolean sandboxEnabled;
    private final int maxPatchAttempts;
    private final boolean githubEnabled;

    public IncidentWorkflow(IncidentRepository incidents, AuditEventRepository auditEvents,
                            RepositoryEvidenceCollector evidenceCollector,
                            IsolatedWorkspaceFactory workspaceFactory, PatchApplier patchApplier,
                            HealingAttemptService attemptService,
                            ObjectProvider<DiagnosisProvider> diagnosisProviders,
                            ObjectProvider<PatchPlanner> patchPlanners,
                            ObjectProvider<SandboxRunner> sandboxRunners,
                            ObjectProvider<PullRequestService> pullRequestServices, ObjectMapper objectMapper,
                            @Value("${agent.workflow.enabled:false}") boolean enabled,
                            @Value("${agent.kill-switch:false}") boolean killSwitch,
                            @Value("${agent.sandbox.enabled:false}") boolean sandboxEnabled,
                            @Value("${agent.max-patch-attempts:2}") int maxPatchAttempts,
                            @Value("${agent.github.enabled:false}") boolean githubEnabled) {
        this.incidents = incidents;
        this.auditEvents = auditEvents;
        this.evidenceCollector = evidenceCollector;
        this.workspaceFactory = workspaceFactory;
        this.patchApplier = patchApplier;
        this.attemptService = attemptService;
        this.diagnosisProviders = diagnosisProviders;
        this.patchPlanners = patchPlanners;
        this.sandboxRunners = sandboxRunners;
        this.pullRequestServices = pullRequestServices;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.killSwitch = killSwitch;
        this.sandboxEnabled = sandboxEnabled;
        this.maxPatchAttempts = Math.max(1, Math.min(maxPatchAttempts, 3));
        this.githubEnabled = githubEnabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentSubmitted(IncidentSubmittedEvent event) {
        if (enabled) {
            process(event.incidentId());
        }
    }

    public void process(UUID incidentId) {
        IncidentEntity incident = incidents.findById(incidentId).orElse(null);
        if (incident == null || incident.getStatus() != IncidentStatus.RECEIVED || isTerminal(incident.getStatus())) {
            return;
        }
        if (killSwitch) {
            finish(incident, IncidentStatus.NEEDS_HUMAN, "Workflow stopped by the server kill switch",
                    null, null);
            return;
        }
        try {
            incident.updateStatus(IncidentStatus.INVESTIGATING, "Collecting allowlisted repository evidence", null);
            incidents.save(incident);
            RepositoryContext context = evidenceCollector.collect(incident);
            incident.updateStatus(IncidentStatus.DIAGNOSING, "Generating an evidence-cited diagnosis", null);
            incidents.save(incident);
            DiagnosisProvider provider = diagnosisProviders.getIfAvailable();
            if (provider == null) {
                finish(incident, IncidentStatus.NEEDS_HUMAN, "No diagnosis provider is configured", null, null);
                return;
            }
            DiagnosisReport diagnosis = provider.diagnose(incident, context);
            validateEvidence(diagnosis, context);
            String diagnosisJson = objectMapper.writeValueAsString(diagnosis);
            String evidenceJson = objectMapper.writeValueAsString(context.evidence());
                incident.recordDiagnosis(diagnosisJson, evidenceJson, IncidentStatus.DIAGNOSING,
                    "Diagnosis complete", null);
                incidents.save(incident);
                if (diagnosis.requiresHumanReview() || diagnosis.confidence() < 0.65) {
                finish(incident, IncidentStatus.NEEDS_HUMAN,
                    "Diagnosis is uncertain or requires human review", diagnosisJson, evidenceJson);
                return;
                }
                runPatchAttempts(incident, diagnosis, context, diagnosisJson, evidenceJson);
        } catch (RepositoryInspectionException exception) {
            finish(incident, IncidentStatus.NEEDS_HUMAN, exception.getMessage(), null, null);
        } catch (JsonProcessingException exception) {
            finish(incident, IncidentStatus.FAILED, "Could not serialize the diagnosis result", null, null);
        } catch (RuntimeException exception) {
            finish(incident, IncidentStatus.FAILED, "Diagnosis workflow failed; inspect sanitized server logs", null, null);
        }
    }

        private void runPatchAttempts(IncidentEntity incident, DiagnosisReport diagnosis, RepositoryContext context,
                      String diagnosisJson, String evidenceJson) throws JsonProcessingException {
        PatchPlanner planner = patchPlanners.getIfAvailable();
        SandboxRunner sandboxRunner = sandboxRunners.getIfAvailable();
        if (planner == null || sandboxRunner == null || !sandboxEnabled) {
            finish(incident, IncidentStatus.NEEDS_HUMAN,
                "Patch planner or isolated sandbox validation is not enabled", diagnosisJson, evidenceJson);
            return;
        }

        int totalAttempts = maxPatchAttempts;
        String previousFailure = null;
        for (int attemptNumber = 1; attemptNumber <= totalAttempts; attemptNumber++) {
            incident.updateStatus(IncidentStatus.PATCHING,
                "Preparing isolated patch attempt " + attemptNumber + " of " + totalAttempts, null);
            incidents.save(incident);
            try (IsolatedWorkspace workspace = workspaceFactory.create(incident)) {
            PatchPlan plan = planner.plan(incident, diagnosis, context, previousFailure);
            if (plan == null || plan.requiresHumanReview() || plan.unifiedDiff() == null
                || plan.unifiedDiff().isBlank()) {
                finish(incident, IncidentStatus.NEEDS_HUMAN,
                    "Patch plan was empty or requires human review", diagnosisJson, evidenceJson);
                return;
            }
            List<String> changedFiles = patchApplier.apply(workspace, plan.unifiedDiff());
            incident.updateStatus(IncidentStatus.TESTING, "Running allowlisted Maven tests in Docker", null);
            incidents.save(incident);
            ValidationResult validation = sandboxRunner.validate(workspace);
            attemptService.record(incident, attemptNumber, plan.summary(), changedFiles, validation);
            if (validation.passed()) {
                String testResult = objectMapper.writeValueAsString(validation);
                incident.recordDiagnosis(diagnosisJson, evidenceJson, IncidentStatus.VALIDATED,
                    "Docker validation passed; human review is required before merge", null);
                incident.recordAttempt(testResult, objectMapper.writeValueAsString(changedFiles), attemptNumber);
                incidents.save(incident);
                auditEvents.save(new AuditEventEntity(incident.getId(), "AGENT", "PATCH_VALIDATED",
                    "Maven tests passed in the isolated Docker sandbox"));
                    if (githubEnabled) {
                    createPullRequest(incident, workspace, plan, changedFiles, testResult,
                        diagnosisJson, evidenceJson);
                    }
                return;
            }
            previousFailure = validation.output();
            incident.updateStatus(IncidentStatus.RETRY_ANALYSIS,
                "Sandbox validation failed with exit code " + validation.exitCode(), null);
            incidents.save(incident);
            } catch (java.io.IOException exception) {
            finish(incident, IncidentStatus.NEEDS_HUMAN,
                "Isolated workspace could not be safely cleaned up", diagnosisJson, evidenceJson);
            return;
            } catch (RuntimeException exception) {
            finish(incident, IncidentStatus.NEEDS_HUMAN,
                "Patch was rejected or could not be safely applied", diagnosisJson, evidenceJson);
            return;
            }
        }
        finish(incident, IncidentStatus.NEEDS_HUMAN,
            "Validation failed after the bounded retry limit; no fix is claimed", diagnosisJson, evidenceJson);
        }

    private void validateEvidence(DiagnosisReport diagnosis, RepositoryContext context) {
        if (diagnosis == null || !Double.isFinite(diagnosis.confidence())
                || diagnosis.confidence() < 0 || diagnosis.confidence() > 1) {
            throw new IllegalArgumentException("Diagnosis confidence is invalid");
        }
        List<String> evidenceIds = context.evidence().stream().map(item -> item.id()).toList();
        if (!evidenceIds.containsAll(diagnosis.evidenceReferences())) {
            throw new IllegalArgumentException("Diagnosis cited evidence that was not collected");
        }
        List<String> evidencePaths = context.evidence().stream().map(item -> item.path()).toList();
        if (!evidencePaths.containsAll(diagnosis.suspectedFiles())) {
            throw new IllegalArgumentException("Diagnosis cited a source file that was not collected");
        }
    }

    private void createPullRequest(IncidentEntity incident, IsolatedWorkspace workspace, PatchPlan plan,
                                  List<String> changedFiles, String testResult, String diagnosisJson,
                                  String evidenceJson) {
        PullRequestService service = pullRequestServices.getIfAvailable();
        if (service == null) {
            finish(incident, IncidentStatus.NEEDS_HUMAN,
                    "GitHub PR integration is enabled but not configured", diagnosisJson, evidenceJson);
            return;
        }
        try {
            PullRequestResult pullRequest = service.create(incident, workspace, plan.summary(), changedFiles, testResult);
            incident.recordPullRequest(pullRequest.url());
            incidents.save(incident);
            auditEvents.save(new AuditEventEntity(incident.getId(), "AGENT", "PULL_REQUEST_CREATED",
                    "Created a GitHub pull request; human review is required"));
        } catch (RuntimeException exception) {
            finish(incident, IncidentStatus.NEEDS_HUMAN,
                    "Patch validation passed but GitHub PR creation failed", diagnosisJson, evidenceJson);
        }
    }

    private void finish(IncidentEntity incident, IncidentStatus status, String escalation,
                        String diagnosisJson, String evidenceJson) {
        incident.recordDiagnosis(diagnosisJson, evidenceJson, status, escalation, escalation);
        incidents.save(incident);
        auditEvents.save(new AuditEventEntity(incident.getId(), "AGENT", "WORKFLOW_FINISHED", escalation));
    }

    private boolean isTerminal(IncidentStatus status) {
        return status == IncidentStatus.VALIDATED || status == IncidentStatus.PR_CREATED
            || status == IncidentStatus.COMPLETED || status == IncidentStatus.NEEDS_HUMAN
                || status == IncidentStatus.FAILED || status == IncidentStatus.REJECTED;
    }
}