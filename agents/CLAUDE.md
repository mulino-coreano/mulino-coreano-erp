# agents/ — L2 agents layer

Role skills describe Claude Code and Codex sessions (Cowork); the first automated runtime targets Codex. The local lease server and Node runner are implemented, but the executable Zig CLI, dedicated runtime image, actual Codex model run and native role hand-off acceptance remain pending at the Task 3/4 milestone. There is no custom agent framework or A2A protocol.

The authenticated human MCP server is implemented separately in `../mcp-server/`; it is not deferred and is not inherited by model containers. Current implementation and verification boundaries are documented in [execution and planning APIs](../docs/13_execution_and_plan_api.md).

| Dir | Contents |
|---|---|
| `cli/` | Planned Zig `mulino` binary and CLI contract; executable implementation is the next step |
| `skills/` | Role instructions for orchestrator / supply-chain / procurement / qc; command and hand-off integration remains pending |
| `runner/` | Implemented Node worker: Auth0 M2M, claim/poll/heartbeat/finish, child-process lifecycle, Docker invocation contract and local tests |

## How it fits together

- A skill teaches the session its role. The planned `mulino` command surface calls the Spring Boot API; calculation and state validation stay on the server. Do not treat a documented future CLI command as already executable.
- Human Case intake records the actual requester and atomically creates an initial Work Item and QUEUED Run. A queued record is not evidence that a model is running. The worker claims only eligible CODEX Work Item runs and maintains one active lease per worker and Work Item.
- Model actions use a short-lived capability bound to the current Run, Case, Work Item and assigned role. The server revalidates that scope on writes. Keep Auth0 M2M secrets, human tokens and DB credentials outside the model environment.
- The plan API persists actual source snapshots, results, immutable versions and hashes. It honors the human's warehouse, products and target date. SUPPLY_CHAIN completion requires the server-owned latest attempt to be READY and point to that Work Item's latest owned plan; caller metadata and old successful receipts cannot override a newer failure.
- Current APIs enforce authentication and scoped actions. Purchasing proposals, MANAGER decisions, ERP purchase application and other L1 write adapters remain pending. Neither CLI nor skills may implement a DB-write or approval bypass.
- Native subagent dispatch is the intended role-collaboration mechanism. Persist responsibilities and hand-offs through supported Case/Work Item APIs; actual model hand-off acceptance is still pending. Adding a role also requires registered identity, capability rules, completion validation and runtime tests, not just a skill folder.

## Retry and execution contracts

- Case, plan and agent Work Item mutations require `Idempotency-Key`; retries of the same logical request retain the key. Replaying a plan receipt does not update the latest-attempt marker.
- Claim credential issuance is intentionally non-replayable: only token hashes are stored. One active worker lease prevents duplicate claims; a lost response requires the runner's 60-second cooldown. Do not promise that every administrative endpoint uses the business idempotency store.
- The runner polls every 5 seconds, heartbeats every 15 seconds, and enforces a 60-second lease and 600-second execution limit. Waiting closes the Run; dependency/time events can queue another Run. Preserve authoritative terminal receipts even if a process later fails.
- The host config `MULINO_AGENT_API_URL` is mapped to the container's CLI variable `MULINO_API_URL`; `MULINO_TOKEN` contains only the scoped capability. The dedicated image/login volume and actual Docker/model execution must be verified separately from local subprocess tests.

## Build order constraint

The CLI's command surface is derived from implemented backend endpoints. Case/plan agent reads, scoped plan calculation and Work Item creation/transition now exist; implement the corresponding CLI commands against those contracts in Task 5. Procurement/approval commands remain blocked on their backend adapters. Synchronize affected role skills and tests when the command surface changes, and do not mark actual Codex execution accepted until the CLI, image and real run have been verified.

Role duties are specified in the agent intervention summary of [docs/02_flow.md](../docs/02_flow.md) (SSOT). The governance approval matrix is in the root `CLAUDE.md`.
