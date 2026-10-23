# Self-Healing Java Bug Fix Agent

An evidence-driven Java 21/Spring Boot service that accepts a log or stack trace, inspects one server-allowlisted clean Java repository, proposes a constrained patch, validates it with a fixed Docker/Maven test command, and reports real results. Merge and production deployment are not implemented.

## Current status

Incident intake, HTTP Basic authentication, request validation/size limits, secret redaction, PostgreSQL/Flyway persistence, audit events, status/result APIs, bounded repository evidence collection, structured AI ports, isolated Git workspaces, Java-only patch allowlisting, bounded sandbox retries, and truthful validation status are implemented.

The safe defaults are workflow disabled, AI offline, sandbox disabled, PR disabled, and deployment disabled. A GitHub PR adapter is implemented behind configuration but has not been integration-tested. Durable queue/restart recovery, tenant authorization, Jira, observability, RAG, and a UI are not implemented. The test suite mocks provider/sandbox outcomes; it does not verify real provider, Docker, or GitHub calls.

## Important operating model

The agent automates investigation, patch generation, and validation. It must never claim that a bug is fixed unless actual build/test evidence supports that claim. GitHub PR creation can be automated after validation. Merge and production deployment require human approval by default.

## Prerequisites

- JDK 21
- Maven (or use the Maven Wrapper)
- Docker Engine/Desktop
- PostgreSQL (or Docker Compose)
- Git
- A supported LLM API key for AI mode
- GitHub App/token only when GitHub integration is enabled
- Jira credentials only when Jira integration is enabled

## Quick start

1. Review `PLAN.md`, `ARCHITECTURE.md`, and `ACCEPTANCE_CRITERIA.md`.
2. Copy `.env.example` to `.env` and replace the local API/database passwords.
3. Start dependencies with `docker compose --env-file .env up -d postgres`.
4. Load local values into the shell with `set -a && source .env && set +a`.
5. Build with `./mvnw clean verify` (Windows: `mvnw.cmd clean verify`).
6. Run with `./mvnw spring-boot:run`.
7. Check `GET /actuator/health`.
8. Submit an incident using the API example below and poll its status.

The local API account defaults to `agent` / `change-me-local-only`. Replace it before exposing the service. Never commit `.env`.

## Submit an incident

`POST /api/incidents`

```json
{
  "serviceName": "order-service",
  "repository": "owner/repository",
  "branch": "main",
  "errorMessage": "NullPointerException in OrderService",
  "stackTrace": "Paste the relevant sanitized stack trace here",
  "environment": "test",
  "correlationId": "optional-request-or-trace-id"
}
```

Expected response:

```json
{
  "incidentId": "generated-incident-id",
  "status": "RECEIVED",
  "statusUrl": "/api/incidents/generated-incident-id"
}
```

Requests require HTTP Basic authentication, are limited to 64 KiB, redact likely secrets, and return HTTP 202. Send `Idempotency-Key` to prevent duplicate submissions.

```bash
curl --user "$AGENT_API_USERNAME:$AGENT_API_PASSWORD" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: demo-001' \
  --data '{"serviceName":"orders","repository":"owner/repository","branch":"main","errorMessage":"Unexpected result","stackTrace":"at demo.Calculator.add(Calculator.java:12)","environment":"test"}' \
  http://localhost:8080/api/incidents
```

## Check status and result

- `GET /api/incidents/{incidentId}` — state, timestamps, and current stage.
- `GET /api/incidents/{incidentId}/result` — diagnosis, evidence, attempts, changed files, actual test results, PR URL, and escalation reason if any.

Endpoints use one configured Basic-auth account. Per-user/tenant ownership is not implemented; do not expose this single-account service to untrusted networks.

Interactive API documentation is available at `/swagger-ui.html`; the OpenAPI JSON document is at `/v3/api-docs`. Documentation routes are public, while the documented incident operations require HTTP Basic authentication. Use the **Authorize** control in Swagger UI with the configured API username and password.

## Expected agent behavior

1. Sanitize and classify the log.
2. Inspect the configured repository and relevant source/tests.
3. Produce a root-cause hypothesis with evidence and confidence.
4. Create a minimal patch on an isolated branch/workspace.
5. Run actual build and tests in a disposable sandbox.
6. If validation fails, analyze the real output and retry within the configured budget.
7. Create a GitHub PR only after validation passes and `AUTO_CREATE_PR=true`; this adapter is implemented but not live-tested.
8. Return a truthful report. If context is missing or confidence is low, ask for additional evidence or escalate.

## Configuration

Use environment variables for secrets and runtime options. Never commit `.env`.

See `.env.example` for configuration. To enable diagnosis, set `AGENT_ENABLED=true`, configure `AGENT_REPOSITORY_ID`, `AGENT_REPOSITORY_ROOT`, `AGENT_ALLOWED_BRANCHES`, `AGENT_AI_MODE=openai`, and `AI_PROVIDER_API_KEY`. The repository root must be a clean checkout on an allowlisted branch.

Patch validation also needs `SANDBOX_ENABLED=true`, a Docker daemon visible to the API process, the pinned Maven image already available locally, and a matching offline Maven dependency cache. `MAX_PATCH_ATTEMPTS` is the total attempt budget (default two, hard maximum three). Only `mvn test` is run; model-generated shell commands are never executed.

The default Compose API container does not mount a Docker socket or target checkout. Run the API on the host for sandbox work; do not mount the Docker socket into test containers.

## Development and verification

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

On Windows:

```bat
mvnw.cmd clean verify
mvnw.cmd spring-boot:run
```

The tests use H2 and temporary Git repositories. The known-bug workflow test substitutes a controlled diagnosis and sandbox response, so it proves policy and result handling, not actual Docker/provider execution. Avoid exposing secrets to untrusted project builds.

## Security checklist

- Authenticate incident submission and result access; tenant-level authorization remains future work.
- Limit request and log sizes.
- Redact API keys, passwords, tokens, cookies, PII, and payment data.
- Treat log content and repository text as untrusted data.
- Restrict repository allowlists and branch names.
- Run builds in a sandbox with resource limits and no privileged mode.
- Do not mount the Docker socket into the sandbox.
- Disable outbound network access in test containers by default.
- GitHub PR support is disabled by default and has not been live-tested.
- Keep audit records for model decisions, tool calls, actual commands, exit codes, changed files, and approvals.
- Add a global kill switch and bounded retries.
- Never auto-merge or deploy unreviewed AI-generated code.

## Troubleshooting

### The agent cannot identify the source file
Check that the configured repository is available, the stack trace contains class/package names, and the repository index includes the relevant module. The agent must report missing evidence rather than invent a file path.

### Tests fail after a patch
Preserve the test output and failed state. The agent may retry within its configured budget; otherwise it must create a failure report and escalate.

### Sandbox is unavailable
Check that the Docker CLI can reach its daemon, the configured image is present, the repository path and temporary directory are visible to that daemon, and the Maven dependency cache is complete for offline test execution. The default Compose setup does not provide these mounts.

### The LLM is unavailable
Return a controlled failure state, preserve the incident, and allow retry after configuration or provider recovery. Do not silently report a successful diagnosis.

## Project documents

- [PLAN.md](self_healing_agent_docs/PLAN.md) — baseline, phases, and implementation status.
- [ARCHITECTURE.md](self_healing_agent_docs/ARCHITECTURE.md) — components, data flow, and current boundaries.
- [ACCEPTANCE_CRITERIA.md](self_healing_agent_docs/ACCEPTANCE_CRITERIA.md) — verified and outstanding criteria.
- [PROMPT_FOR_CODING_AGENT.md](self_healing_agent_docs/PROMPT_FOR_CODING_AGENT.md) — implementation requirements.
