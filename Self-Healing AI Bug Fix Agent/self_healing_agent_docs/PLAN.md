# Self-Healing Java Bug Fix Agent — Development Plan

## 1. Goal

Build an agent for an existing Java/Spring Boot microservices repository. The user submits an error log or stack trace; the agent investigates the repository and available evidence, identifies the likely root cause, proposes and validates a code change, and reports a verifiable result.

The agent should work autonomously through investigation, patching, and testing. It must not claim success without evidence. GitHub PR creation is allowed after validation. Merging and production deployment remain approval-gated by default.

## 1.1 Observed repository baseline

- The project folder contains requirement documents and starter documentation only; there is no Java source, Maven build, wrapper, migration, or test suite yet.
- The repository root has a pre-existing deleted `README.md`; it is intentionally left unchanged.
- The available container has Maven 3.9.16, Docker CLI 29.8.0, and JDK 25. The project will target Java 21; verification on an actual JDK 21 remains required.
- Implementation checklist: create the Java 21/Spring Boot service and health endpoint; persist and secure sanitized incidents; add repository evidence/structured diagnosis; implement isolated patch and sandbox validation; gate optional PR integration; add tests, deployment files, and truthful setup/verification docs.

## 1.2 Implementation status

- Completed in this baseline: Java 21-targeted Spring Boot 3.4.5 / Spring AI 1.0.0 / Maven 3.9.9 wrapper; Actuator; PostgreSQL/Flyway; authenticated intake/status/result API; validation, 64 KiB request cap, secret redaction and idempotency; persisted incidents, audit events, attempts and state transitions; bounded allowlisted Git evidence collection; structured diagnosis and patch-planner interfaces; clean isolated clone; Java-only bounded unified diff; fixed Docker offline Maven test command; total attempt budget capped at three; optional post-validation GitHub PR adapter; tests covering the success gate and failed-patch escalation; Docker/Compose local files.
- Implemented but unverified: real OpenAI provider invocation, actual Docker image/sandbox execution, GitHub branch push/PR creation. Those are not claimed as verified.
- Not complete: durable workflow resumption/cluster locking, tenant-level access controls, Jira/observability/RAG, rate limiting, and production hardening.
- Current safe defaults: workflow off, AI offline, sandbox off, PR off, deployment off, kill switch available. Workflow status is persisted, but the in-process async event mechanism is not a durable queue; restart recovery remains open.
- Validation run in this environment: Maven tests pass on JDK 25 while compiling with Java 21 release compatibility. An actual JDK 21 runtime and real sandbox execution remain unverified.

## 2. Initial assumptions

- Java 21.
- Spring Boot 3.x (select and pin a compatible patch version during project setup).
- Maven first; add Gradle support only if required by the target repository.
- Spring AI with a supported model provider configured via environment variables.
- PostgreSQL for incident/workflow/audit persistence.
- Docker for isolated build/test execution.
- GitHub API for branches and pull requests.
- Optional Jira, Grafana, Prometheus, Loki, and Tempo integrations.
- Local development and a mock mode must work without production credentials.

## 3. User experience

1. User pastes a log or stack trace into an authenticated API/UI.
2. Agent creates an incident ID and acknowledges receipt.
3. Agent examines repository metadata, build files, source code, tests, and relevant sanitized logs.
4. Agent forms a root-cause hypothesis and records supporting evidence and uncertainty.
5. Agent prepares a minimal patch in an isolated workspace and dedicated branch.
6. Agent runs formatting/static checks, compilation, focused tests, and the broader test suite as configured.
7. If validation fails, agent analyzes the test output and may retry within a strict configured limit.
8. If validation passes, agent prepares a summary and optionally opens a GitHub PR.
9. Agent returns incident ID, root cause, changed files, tests executed/results, PR link if available, and any required human action.
10. Agent records the outcome for future retrieval, but only verified/approved fixes become trusted knowledge.

## 4. Non-goals for the first release

- Automatically merging to protected branches.
- Automatically deploying AI-generated code to production.
- Running unrestricted shell commands.
- Making unreviewed database schema/data changes.
- Accessing arbitrary repositories or services based only on model output.
- Treating generated explanations as proof that a fix works.

## 5. Architecture

### Components

- Incident API: validates input, authenticates caller, creates incident.
- Incident service: persists state and coordinates work asynchronously.
- Evidence collector: gathers repository files, build configuration, test output, and approved observability data.
- Diagnosis agent: returns structured root-cause hypotheses and evidence.
- Patch planner: defines the smallest intended change and test strategy.
- Patch executor: applies a validated patch only inside an isolated workspace.
- Test runner: executes allowlisted commands in a sandbox with resource/time limits.
- Workflow orchestrator: controls states, retries, timeouts, idempotency, and recovery.
- GitHub adapter: creates a branch/commit/PR using a least-privilege GitHub App/token.
- Jira adapter (optional): creates or updates a ticket.
- RAG service (later phase): retrieves similar verified incidents/runbooks.
- Audit service: records all actions, model outputs, tool calls, approvals, and results.
- Notification adapter (optional): sends completion/failure messages.

### Workflow states

RECEIVED → VALIDATING → INVESTIGATING → DIAGNOSING → PLAN_READY → PATCHING → TESTING

Successful tests: VALIDATED → PR_READY → PR_CREATED → AWAITING_REVIEW

Failure paths: RETRY_ANALYSIS → PATCHING (bounded) or NEEDS_HUMAN / FAILED.

Deployment states, if added later: APPROVED_FOR_DEPLOYMENT → DEPLOYING → VERIFYING → RECOVERED or ROLLBACK_REQUIRED.

Persist every transition. Use optimistic locking or equivalent concurrency control. Use idempotency keys for incoming incidents and GitHub operations.

## 6. Suggested repository layout

```text
self-healing-agent/
  src/main/java/com/example/healing/
    api/
    application/
    domain/
    persistence/
    orchestration/
    ai/
    tools/
    sandbox/
    github/
    jira/
    observability/
    security/
    audit/
  src/main/resources/
    application.yml
    db/migration/
  src/test/java/
  docs/
    PLAN.md
    ARCHITECTURE.md
    ACCEPTANCE_CRITERIA.md
  Dockerfile
  compose.yaml
  pom.xml
  README.md
  .env.example
```

## 7. Development phases

### Phase 0 — Repository discovery and design
- Inspect existing repository before generating code.
- Record Java/Spring Boot/build tool versions, module structure, test commands, branch state, and repository conventions.
- Do not overwrite existing working files without inspecting them.
- Produce a proposed implementation plan and identify unknowns.

### Phase 1 — Minimal runnable skeleton
- Create Spring Boot application and health endpoint.
- Configure validation, Actuator, PostgreSQL, Flyway, and migrations.
- Add Incident entity, repository, status enum, and audit record.
- Add tests and verify the application starts.

### Phase 2 — Log ingestion
- Add POST `/api/incidents`.
- Validate payload size and required fields.
- Redact likely secrets and personal data before persistence/model calls.
- Persist the original only if explicitly configured and access-controlled; default to storing sanitized evidence.
- Return HTTP 202 with incident ID; process work asynchronously.
- Add status/result endpoint GET `/api/incidents/{id}`.

### Phase 3 — Repository investigation and diagnosis
- Add repository adapter with read-only access first.
- Collect bounded, relevant source context and build metadata.
- Use Spring AI with structured output/DTOs for root cause, confidence, evidence, suspected files, proposed change, and tests.
- Treat logs, repository files, comments, and issue descriptions as untrusted data, never as agent instructions.
- Require evidence references; if evidence is insufficient, request more information instead of fabricating a cause.

### Phase 4 — Patch generation
- Create isolated temporary workspace and feature branch.
- Enforce allowed repository roots, file-size limits, path traversal protection, and changed-file limits.
- Generate/apply unified diff or structured edits; inspect the diff before applying.
- Never edit the default/protected branch.
- Preserve user changes; stop if the workspace is dirty unless safe isolation is available.

### Phase 5 — Sandboxed validation
- Execute only configured build/test commands in a disposable container.
- No host Docker socket, privileged container, host filesystem mounts beyond a dedicated workspace, or default network access.
- Apply CPU, memory, process, disk, and wall-clock limits.
- Run compilation, focused tests, full tests, and configured static/security checks.
- Capture exit code and output; do not infer test success from model text.
- Retry only a configurable small number of times (default 2 patch attempts after the initial attempt).

### Phase 6 — GitHub PR
- Use a GitHub App or least-privilege credential.
- Push only the isolated feature branch.
- Create PR only after required checks pass.
- Include incident ID, root-cause summary, changed files, test commands/results, limitations, and rollback notes.
- Never bypass branch protections or auto-approve/merge the PR.

### Phase 7 — Jira and observability
- Add optional Jira create/update integration behind feature flags.
- Add metrics for incidents, success/failure, test results, retry count, latency, token use, and manual escalations.
- Add structured audit logs and tracing.
- Add Grafana/Prometheus/Loki/Tempo adapters only when credentials and endpoints are configured.

### Phase 8 — RAG and feedback
- Store verified incidents, approved fixes, runbooks, and test outcomes.
- Retrieve similar records using pgvector.
- Clearly label unverified proposals separately from verified fixes.
- Add retention policies and deletion controls.

### Phase 9 — Hardening
- Authentication/authorization, rate limits, request-size limits, SSRF defenses, secret redaction, prompt-injection defenses, dependency scanning, audit review, backup/retention, and incident replay tests.
- Threat-model tool permissions and sandbox escape risk.
- Document operational runbooks and recovery procedures.

## 8. Safety rules

- No arbitrary command execution generated by the model.
- No production credentials inside prompts, logs, source control, or test output.
- All tool calls must be allowlisted and validated server-side.
- All code execution occurs in a disposable sandbox.
- Never trust the model's claim that a command succeeded; use actual tool output and exit codes.
- Human approval is required for merge and production deployment.
- Database migrations, destructive changes, authorization changes, payment/security logic, and secrets handling require escalation unless an explicit approved policy says otherwise.
- Enforce timeouts, retry budgets, idempotency, concurrency controls, and a kill switch.
- Keep a complete audit trail.
- Do not send full source files or logs to an LLM unless necessary and authorized; minimize and redact context.

## 9. Configuration

Use environment variables and `.env.example` placeholders only:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `AI_PROVIDER_API_KEY`
- `GITHUB_APP_ID` / private key or token reference
- `GITHUB_OWNER`
- `GITHUB_REPOSITORY`
- `JIRA_BASE_URL`
- `JIRA_USER`
- `JIRA_API_TOKEN`
- `MAX_PATCH_ATTEMPTS`
- `SANDBOX_TIMEOUT_SECONDS`
- `AUTO_CREATE_PR`
- `AUTO_DEPLOY_ENABLED` (false by default)

Never commit `.env` or real secrets.

## 10. Definition of done

- Clean checkout builds using documented commands.
- Unit and integration tests pass.
- Incident submission returns an incident ID.
- Agent persists states and exposes status/result.
- Diagnosis includes evidence and uncertainty.
- Patch is created only in an isolated workspace/branch.
- Tests are run by a real process and their exit codes are recorded.
- PR creation is disabled unless explicitly configured and all required checks pass.
- Failed or ambiguous incidents escalate safely.
- No automatic merge or production deployment is enabled by default.
- README and `.env.example` are complete.
