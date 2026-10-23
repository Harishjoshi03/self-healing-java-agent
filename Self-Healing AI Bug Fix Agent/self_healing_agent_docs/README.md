# Self-Healing Java Bug Fix Agent

An AI-assisted engineering agent that accepts a log or stack trace, investigates an existing Java/Spring Boot repository, proposes a minimal fix, validates it in an isolated environment, and optionally opens a GitHub pull request.

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
2. Copy `.env.example` to `.env` and fill only local development values.
3. Start dependencies with `docker compose up -d postgres`.
4. Build with `./mvnw clean verify` (Windows: `mvnw.cmd clean verify`).
5. Run with `./mvnw spring-boot:run`.
6. Check `GET /actuator/health`.
7. Submit a test incident using the API example below.
8. Poll the incident status until it reaches a terminal state.

Commands are targets for the implementation; update them if the generated project uses different names or profiles.

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

The implementation must authenticate the request, validate fields, redact secrets, and return HTTP 202 when processing asynchronously.

## Check status and result

- `GET /api/incidents/{incidentId}` — state, timestamps, and current stage.
- `GET /api/incidents/{incidentId}/result` — diagnosis, evidence, changed files, test results, PR URL, and escalation reason if any.

Do not expose secrets or unredacted logs through these endpoints. Apply authorization so users can access only incidents they are permitted to view.

## Expected agent behavior

1. Sanitize and classify the log.
2. Inspect the configured repository and relevant source/tests.
3. Produce a root-cause hypothesis with evidence and confidence.
4. Create a minimal patch on an isolated branch/workspace.
5. Run actual build and tests in a disposable sandbox.
6. If validation fails, analyze the real output and retry within the configured budget.
7. Create a PR only if validation passes and PR creation is enabled.
8. Return a truthful report. If context is missing or confidence is low, ask for additional evidence or escalate.

## Configuration

Use environment variables for secrets and runtime options. Never commit `.env`.

See `.env.example` for the names expected by this project. Keep automatic deployment disabled in development and production unless a separate, reviewed deployment policy is implemented.

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

Run integration tests with a disposable test database and isolated test repositories. Do not run untrusted project builds on a developer workstation with access to credentials.

## Security checklist

- Authenticate and authorize incident submission and result access.
- Limit request and log sizes.
- Redact API keys, passwords, tokens, cookies, PII, and payment data.
- Treat log content and repository text as untrusted data.
- Restrict repository allowlists and branch names.
- Run builds in a sandbox with resource limits and no privileged mode.
- Do not mount the Docker socket into the sandbox.
- Disable outbound network access in test containers by default.
- Use least-privilege GitHub credentials.
- Keep audit records for model decisions, tool calls, actual commands, exit codes, changed files, and approvals.
- Add a global kill switch and bounded retries.
- Never auto-merge or deploy unreviewed AI-generated code.

## Troubleshooting

### The agent cannot identify the source file
Check that the configured repository is available, the stack trace contains class/package names, and the repository index includes the relevant module. The agent must report missing evidence rather than invent a file path.

### Tests fail after a patch
Preserve the test output and failed state. The agent may retry within its configured budget; otherwise it must create a failure report and escalate.

### GitHub PR creation fails
Check repository permissions, branch protections, token expiry, branch naming, and whether the configured GitHub identity can create branches and pull requests.

### The LLM is unavailable
Return a controlled failure state, preserve the incident, and allow retry after configuration or provider recovery. Do not silently report a successful diagnosis.

## Project documents

- `PLAN.md` — implementation phases and engineering rules.
- `ARCHITECTURE.md` — components and data flow.
- `ACCEPTANCE_CRITERIA.md` — testable completion requirements.
- `PROMPT_FOR_CODING_AGENT.md` — implementation prompt to paste into your coding assistant.
