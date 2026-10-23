# Acceptance Criteria

## A. Incident intake
- [x] Valid incident request returns HTTP 202 and a unique incident ID.
- [x] Invalid or oversized requests are rejected.
- [x] Likely secrets are redacted before persistence/provider use.
- [x] Incident status is persisted and retrievable.
- [x] Duplicate requests with the same idempotency key return the original incident.

## B. Diagnosis
- [x] Configured clean repository source is inspected based on actual stack frames.
- [x] Structured diagnosis DTO includes evidence, files, proposed change, tests, uncertainty, and risk.
- [x] Diagnosis references are checked against collected files/evidence.
- [x] Provider prompts treat incident/repository text as untrusted.
- [x] Missing repository/source evidence leads to a human escalation.

## C. Patch safety
- [x] Patch is created only in a fresh temporary Git clone and feature branch.
- [x] Configured checkout is never patched.
- [x] Only Java main/test source paths are accepted.
- [x] Patch size and changed-file count are bounded.
- [x] Dirty configured checkout is refused.

## D. Validation
- [x] Validation command is fixed in server code; the model cannot choose commands.
- [x] Docker runner defines network, user, capability, memory, CPU, process, timeout, and output restrictions.
- [x] Actual command, exit code, duration, and redacted output are persisted.
- [x] Failed validation never results in `VALIDATED`.
- [x] Total attempts are configurable and capped at three.
- [x] Temporary workspaces are removed after success/failure.
- [ ] A real Docker image test has not been executed in this environment.

## E. GitHub
- [ ] GitHub adapter exists but live branch/PR calls are not verified.
- [x] No PR is created unless the disabled-by-default feature flag is enabled.
- [ ] GitHub credentials/least-privilege behavior requires operational validation.
- [x] No merge or deployment behavior is implemented.

## F. Reliability and security
- [x] Incident states and allowed transitions are persisted/enforced.
- [ ] Workflow restart recovery and multi-instance claiming are not implemented.
- [x] Sandbox timeout, bounded async queue, request cap, and kill switch exist.
- [ ] Full action/tool audit and secret review need further hardening.
- [x] API authentication protects incident endpoints; tenant authorization/rate limiting remain open.
- [x] Automatic deployment is not implemented.

## G. Documentation
- [x] Maven Wrapper and documented build commands are provided.
- [x] `.env.example` contains placeholders only.
- [x] README documents setup, API, configuration, tests, and limitations.
- [x] Tests cover intake, evidence, patch restrictions, validation success, failed validation/retry, and escalation.

## Demo acceptance scenario
Given a sample Java exception and a local test repository containing a known reproducible bug:
1. [x] Submit the log.
2. [x] Identify a source location with evidence (test fixture).
3. [x] Propose a minimal fix (controlled planner fixture).
4. [x] Apply the patch in an isolated clone/branch.
5. [ ] A real Docker test process is not covered by the fixture test.
6. [ ] PR creation is not implemented.
7. [x] Result reports actual validation status and changed files.

Also test a deliberately incorrect patch. The agent must detect failing tests and must not report success.
