# agents/cli/ — `mulino` CLI (Zig)

Single static binary. Pattern: `mulino <noun> <verb>` where nouns mirror the traceability chain: `supplier`, `cert`, `material`, `po`, `inbound`, `lot`, `production`, `outbound`, `order`, `recall`. Example: `mulino lot trace RM-2026-001 --reverse`.

## Contract (consumers are LLM agents, not humans)

- **JSON on stdout, always.** Errors are JSON on stderr.
- **Exit codes**: `0` = command executed — including governance verdicts (`{"status": "PENDING_APPROVAL", "approval_id": ...}` and `BLOCKED` are data, not failures); `1` = usage error; `2` = network/API failure.
- **No retries** — the calling agent is the retry loop.
- **Config via env only**: `MULINO_API_URL`, `MULINO_TOKEN`. The token carries role identity, which the L1 governance matrix evaluates on writes. No config files.

## Implementation rules

- Zig stdlib only: `std.http.Client` + `std.json`. Zero external dependencies.
- Zig is pre-1.0 and `std.http` churns between releases — pin the toolchain via `minimum_zig_version` in `build.zig.zon`.
- The CLI is dumb: no domain logic, no validation beyond argument parsing. Domain rules live in the backend; role behavior lives in the skills.
- Tests: built-in `test` blocks for arg parsing and response shaping; one smoke test against a stub HTTP server.

## When changing the command surface

The four skills in `../skills/` reference `mulino` commands by name. When adding, renaming, or changing the output shape of a subcommand, update the affected `SKILL.md` files in the same change.
