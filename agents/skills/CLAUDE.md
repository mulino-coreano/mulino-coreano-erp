# agents/skills/ — agent role skills

One directory per agent role, each holding a `SKILL.md`. These are authored here as the SSOT and symlinked into `.claude/skills/` (Claude Code) and `.codex/skills/` (Codex) so sessions auto-discover them (symlinks created in Phase 6, when the skills gain content).

## Required sections in every SKILL.md

1. **Mission** — the role's one-paragraph purpose
2. **Allowed commands** — which `mulino` subcommands this role may use (see `../cli/CLAUDE.md` for the contract)
3. **Governance expectations** — which of its writes return `PENDING_APPROVAL` and what to do then (e.g. Procurement: PO creation always pends MANAGER approval — never treat it as failure)
4. **Korea localization invariants it guards** — from the root `CLAUDE.md`
5. **Hand-off triggers** — situations where the role reports back to the orchestrator for another role to take over

The orchestrator skill additionally defines the dispatch table: which role subagent handles which situation, and how results flow back.

## Rules

- Role duties come from the agent intervention summary in `docs/02_flow.md` (SSOT) — don't invent new duties in a skill without updating the flow doc.
- Adding an agent = new folder + `SKILL.md` + symlink. No code.
- Skills contain role knowledge only — no credentials, no endpoint URLs (that's `MULINO_API_URL`), no SQL.
