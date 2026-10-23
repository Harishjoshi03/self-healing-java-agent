# Architecture — Self-Healing Bug Fix Agent

## System boundary

The agent is a control-plane service. Target repositories and services are treated as external, untrusted systems. The agent must have explicit allowlists and least-privilege credentials.

## Data flow

1. Incident API validates and redacts submitted logs.
2. Incident record and audit event are persisted.
3. Workflow orchestrator starts a durable asynchronous workflow.
4. Evidence collector reads approved repository files and approved observability data.
5. Diagnosis component returns structured hypotheses with source evidence.
6. Patch planner creates a bounded patch plan and test plan.
7. Patch executor applies changes in an isolated workspace.
8. Sandbox runner runs allowlisted commands and captures actual exit codes/output.
9. Policy engine decides whether to retry, escalate, or permit PR creation.
10. GitHub adapter creates a branch/PR if enabled and validation passed.
11. Result service reports verified facts and remaining uncertainty.

## Core interfaces

Suggested interfaces; adapt names to the selected implementation:

- `IncidentService.submit(IncidentRequest) -> IncidentId`
- `IncidentWorkflow.start(IncidentId)`
- `EvidenceCollector.collect(IncidentContext) -> EvidenceBundle`
- `DiagnosisService.diagnose(EvidenceBundle) -> DiagnosisReport`
- `PatchPlanner.plan(DiagnosisReport, RepositoryContext) -> PatchPlan`
- `PatchExecutor.apply(PatchPlan, IsolatedWorkspace) -> PatchResult`
- `SandboxRunner.validate(Workspace, ValidationPlan) -> ValidationReport`
- `HealingPolicy.evaluate(Incident, DiagnosisReport, PatchResult, ValidationReport) -> Decision`
- `PullRequestService.create(ValidatedPatch) -> PullRequestResult`
- `AuditService.record(AuditEvent)`

## Data model

### Incident
- id
- serviceName
- repositoryIdentifier
- requestedBaseBranch
- sanitizedErrorSummary
- sanitizedStackTrace
- correlationId
- state
- createdAt / updatedAt
- attemptCount
- finalOutcome

### HealingAttempt
- id
- incidentId
- attemptNumber
- diagnosisJson
- patchSummary
- changedFiles
- buildExitCode
- testExitCode
- validationSummary
- startedAt / completedAt

### AuditEvent
- id
- incidentId
- actorType (USER, AGENT, TOOL, APPROVER)
- action
- sanitizedInputSummary
- resultSummary
- timestamp
- correlationId

### PullRequestRecord
- incidentId
- repository
- branch
- pullRequestUrl
- validationStatus
- createdAt

Avoid storing full raw logs by default. Apply retention and access policies.

## State transitions

- RECEIVED → VALIDATING
- VALIDATING → INVESTIGATING or REJECTED
- INVESTIGATING → DIAGNOSING
- DIAGNOSING → PLAN_READY or NEEDS_HUMAN
- PLAN_READY → PATCHING
- PATCHING → TESTING
- TESTING → VALIDATED or RETRY_ANALYSIS or FAILED
- RETRY_ANALYSIS → PATCHING if retry budget remains
- VALIDATED → PR_CREATED if enabled, otherwise COMPLETED
- Any active state → NEEDS_HUMAN or FAILED on unrecoverable failure
- PR_CREATED → AWAITING_REVIEW

Do not let an LLM directly set state. The orchestrator and policy engine own transitions.

## Sandboxing model

- Use a disposable container or similarly isolated worker.
- Mount only the temporary repository workspace.
- No privileged mode, host PID namespace, Docker socket, or host credentials.
- Run as non-root where possible.
- Apply CPU, memory, disk, process, and timeout limits.
- Network disabled by default; allow only explicit dependencies/mirrors if needed.
- Destroy the worker after collecting output.
- Validate file paths and reject path traversal or symlink escapes.

## AI response schema

Diagnosis response should include:
- `summary`
- `likelyRootCause`
- `confidence` (bounded numeric value)
- `evidence[]` (file path/line or log excerpt reference)
- `suspectedFiles[]`
- `proposedChange`
- `testsToRun[]`
- `missingInformation[]`
- `riskLevel`
- `requiresHumanReview`

Treat confidence as a heuristic, not a guarantee. Require source evidence and test results for claims.

## Failure behavior

- Model/provider unavailable: preserve incident and mark retryable failure.
- Repository unavailable: retry transient errors, then request access or escalate.
- Ambiguous cause: return ranked hypotheses and ask for targeted missing evidence.
- Tests fail: bounded retry; otherwise provide test output and stop.
- GitHub failure: retain validated patch and report PR integration failure.
- Orchestrator restart: resume from persisted state without duplicate side effects.

## Implemented baseline and current boundaries

The runnable service is under the project root next to this document. It uses Spring Boot 3.4.5, Spring AI 1.0.0, Java release 21, Maven Wrapper 3.9.9, PostgreSQL/Flyway, and an H2-backed test profile. The current API uses HTTP Basic authentication with a single configured account; per-user incident ownership and authorization are not implemented.

`IncidentWorkflow` is the sole workflow coordinator. `IncidentStatus` validates transitions. Diagnosis and patch planner interfaces isolate the provider; offline diagnosis escalates without a cause. The repository adapter reads only a server-configured clean checkout and source frames actually present in the submitted stack trace. Model evidence IDs and file paths are validated against collected evidence.

Patches are limited to small text diffs under Java main/test source directories. A new local Git clone and branch are used per attempt; the configured checkout is never patched. The sandbox runner invokes a fixed offline Maven test command using a pinned image, no network, non-root UID/GID, read-only root, resource caps, timeout, bounded output, and actual process exit status. A successful result requires `sandboxed=true` and exit code zero.

The API Compose service does not mount the Docker socket or a daemon-visible temporary workspace. Consequently, sandbox execution is intended for a host-run agent process or a separately designed worker deployment, not the default Compose agent. Docker calls, actual provider responses, and GitHub PR operations have not yet been verified. The async event listener and persisted state do not currently provide durable job recovery or multi-instance claim locking.

GitHub PR creation has an adapter and is gated by `AUTO_CREATE_PR`, but its live API/push path is unverified. Jira, observability, vector search, and tenant-aware auth remain future work. Keep PR creation disabled until credentials and the remote operation are independently validated.
