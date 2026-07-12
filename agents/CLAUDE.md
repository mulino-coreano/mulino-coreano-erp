# agents/ — L2 agents layer

Claude sessions (Claude Code / Cowork) are the agent runtime. This directory contains **no agent framework, no LLM client, no A2A, and no MCP implementation** — only the two things Claude needs to act as ERP agents. An MCP Server is deferred pending a complexity-management decision.

| Dir | Contents |
|---|---|
| `cli/` | Zig source for the single `mulino` binary — the agents' only access to the backend |
| `skills/` | One skill per agent role (orchestrator / supply-chain / procurement / qc) |

## How it fits together

- A skill teaches a Claude session its role; every backend interaction the session performs goes through `mulino`, which calls the Spring Boot REST API (L0).
- Writes are gated by the governance engine (L1) **inside the backend** — the CLI and skills cannot bypass it because they have no DB access.
- Agent-to-agent collaboration is Claude's native subagent dispatch: the orchestrator is the main session and launches role subagents. There is no wire protocol between agents.
- Adding a new agent = adding one folder under `skills/` — no code changes.

## Build order constraint

The CLI's command surface is derived from the backend REST endpoints (Phase 4). Do not implement `cli/` before those endpoints exist; skills reference CLI commands, so they firm up last (Phase 6).

Role duties are specified in the agent intervention summary of `docs/02_flow.md` (SSOT). The governance approval matrix is in the root `CLAUDE.md`.
