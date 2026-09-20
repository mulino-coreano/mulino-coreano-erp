# AGENTS.md

This file is the operating guide for agent sessions working in this repository, and the only place this guidance is written. Codex reads it natively; Claude Code does not, so root `CLAUDE.md` is a symlink to it rather than a second copy. Directory-scoped guidance lives in a nested `AGENTS.md`.

## Project Overview

A hypothetical ERP + AI agent governance system assuming Mulino Bianco (an Italian food brand) enters the Korean market. A SAP consulting portfolio project that localizes a EU-standard ERP to Korean food regulations (Food Traceability Act, 22 allergens, electronic tax invoices, etc.).

**Current status**: Phase 4 in progress (L0 backend · interface). On `main`: a Spring Boot application with Flyway migrations `V1`–`V7`, a common response/exception layer and Swagger. `governance/` and `dashboard/` are still empty scaffolds; `agents/` holds the role skills and layout guidance only — the `mulino` CLI is not built yet. What remains in Phase 4, and every Phase after it, lives on the project board rather than in this file. All documentation is written in Korean.

## Commands

Backend (Gradle wrapper, Java 21) — needs a reachable PostgreSQL:

```bash
cd backend
./gradlew test
./gradlew bootRun
```

The application applies Flyway migrations (`backend/src/main/resources/db/migration/`, `V1`–`V7`) on startup. The standalone DDL in `database/ddl/` is the schema's readable SSOT and must stay in sync with them. To build a database from the DDL directly:

```bash
# After creating the DB, run in FK-dependency order (file number order is mandatory)
createdb mulino_coreano
psql -d mulino_coreano -f database/ddl/00_types.sql
psql -d mulino_coreano -f database/ddl/01_master_tables.sql
psql -d mulino_coreano -f database/ddl/02_relation_tables.sql
psql -d mulino_coreano -f database/ddl/03_transaction_tables.sql
psql -d mulino_coreano -f database/ddl/04_indexes.sql
psql -d mulino_coreano -f database/ddl/05_foreign_keys.sql
psql -d mulino_coreano -f database/ddl/06_audit_immutability.sql
psql -d mulino_coreano -f database/seed/allergens.sql
```

**Planned stack** (new code follows this baseline): Backend is Spring Boot 4.1.x + Java 21 + Gradle exposing a REST API. Agents are Claude Code or Codex sessions (Cowork) driven by per-role skills; their only backend access is a single Zig CLI (`mulino`) that calls the REST API. Dashboard is React 19 + Vite. There is no A2A protocol — the runtime's native subagent dispatch (Claude Code or Codex) replaces it. An MCP Server is deferred pending a complexity-management decision; until then, the CLI is the sole tool surface.

## Architecture (4 layers = directory mapping)

| Layer | Directory | Role |
|---|---|---|
| L0 | `database/`, `backend/` | PostgreSQL 18 (30 tables) + Spring Boot REST API (single entry point for CLI and dashboard) |
| L1 | `governance/` | Intercept action-bearing API calls → approve / block / hold + audit log. **Reads pass through; only writes are gated** |
| L2 | `agents/` | `cli/` (Zig `mulino` binary) + `skills/` (orchestrator / supply-chain / procurement / qc). Claude Code and Codex are both supported agent runtimes; the orchestrator dispatches role subagents. See `agents/AGENTS.md` |
| L3 | `dashboard/` | Natural-language query → Intent Parsing → chart generation |

### Governance approval matrix (follow when implementing L1)

- `INSERT purchase_orders` → MANAGER approval
- `UPDATE inbound` (block/hold) → QC approval
- `INSERT recalls` → ADMIN approval
- `UPDATE production_lots.status = 'RECALLED'` → ADMIN approval

### Bidirectional LOT traceability chain (never break this when changing the schema)

```
suppliers → purchase_orders → purchase_order_items → inbound
→ raw_material_lots → production_ingredients → production_lots
→ outbound_lots → outbound → orders → customers
```

Reverse tracing (root-cause analysis) follows this chain backwards. Core invariants: `SUM(outbound_lots.lot_quantity) = outbound.quantity`, and `raw_material_lots.remaining_quantity` is decremented on production input.

### Korea localization invariants (the reason this design exists — do not change arbitrarily)

- 22 allergens (`allergens` master, not the EU 14)
- Electronic tax invoice columns (`tax_invoice_number` / `tax_invoice_date` — purchase_orders, orders)
- Recall: report to MFDS immediately, retain records for 2 years
- Certificates: HACCP/GMP/traceability registration (notify 30 days before expiry, block inbound on expiry)

The full flow and agent intervention points are the single source of truth (SSOT) in `docs/02_flow.md`. For the table list and SAP module mapping, see `docs/01_project_overview.txt`.

## Work comes from the board

Every goal this project has lives in GitHub. There is one board — [Mulino Coreano — ERP & Agent Governance](https://github.com/orgs/mulino-coreano/projects/1) — and its operating rules are in `docs/06_labels.md`, which outranks this file.

- **Goals are milestones (Phases) and issues.** A goal written only in a doc is an untracked goal.
- **Check the board when a session starts.** The `goals` skill has the commands for the current Phase and its goals; the `backlog` skill has the commands for the next candidate. The board is more current than the status described in this file or in `docs/`.
- **Do not start work that has no issue.** If nothing on the board covers it, stop and ask — do not create an issue and carry on by yourself. Questions, investigation and typo fixes are exempt.
- **Do not start work outside the current Phase on your own initiative.** Propose it; the human decides.
- **Never set the board's Status field by hand.** Automation derives it from issue and PR state.

Carrying out an issue is the `backlog` skill; changing what the goals are is the `goals` skill (both in `.agents/skills/`).

## Git rules

- **No direct commit/push to main** — work on a separate branch, then open a PR to merge (force push is strictly forbidden)
- Commit message prefixes: `feat` | `chore` | `fix` | `docs` (e.g. `feat(migration): create migration files`)

## Conventions
- The issue/PR label scheme is in `docs/06_labels.md` (category + `L0-db`~`L3-dashboard` layer labels)
- Never commit secrets (`application-local.yml`, `.env`) — already in `.gitignore`
- On schema changes, keep `docs/02_flow.md` consistent with the ERD (Phase 1 required "flow diagram–ERD 100% consistency" as an acceptance criterion)
- Skills live in `.agents/skills/` — dev-workflow skills (`backlog` to carry out an issue, `goals` to change what the goals are) as real directories, ERP role skills as symlinks to `agents/skills/`, which stays their SSOT (the L2 product layer). Codex discovers that directory natively.
- Claude Code does **not** read `.agents/skills/`, so `.claude/skills/` mirrors it with one symlink per skill. Add a skill in both places, and point the `.claude/skills/` link at the skill's real directory — never at another symlink.

## Issue/PR templates (mandatory)

Templates live in `.github/`. When creating issues or PRs, the session must follow the structure of the relevant template exactly — do not omit sections or invent your own format.

**PR**: `.github/pull_request_template.md` — 4 sections (작업 내용 / 변경 사항 / 체크리스트 / 리뷰 요청 사항). The 3 checklist items (verify local run, do not commit secrets, update related docs) must appear in the PR body.

**Korean PR requirement**: Always create pull requests from `.github/pull_request_template.md`, and write both the PR title and the entire PR body in Korean.

**Issues**: pick one of the following by task type (title prefix and label are auto-applied).
- `bug.md` — `[BUG]` · `bug` — bug report (bug description / reproduction steps / expected & actual behavior / environment)
- `feature.md` — `[FEAT]` · `feature` — feature development (overview / L0–L3 layer checkboxes / detailed tasks / definition of done / references)
- `qc.md` — `[QC]` · `qc` — QC/test (target / items / method / result Pass·Fail)
- `research.md` — `[RESEARCH]` · `research` — upfront research (purpose / items / result + sources / design impact / sources)

Check the `config.yml` contact link (project docs) before creating an issue. Blank issues are allowed, but for types that have a template, using the template is the default.
