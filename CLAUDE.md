# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository. Codex sessions are also supported — see AGENTS.md.

## Project Overview

A hypothetical ERP + AI agent governance system assuming Mulino Bianco (an Italian food brand) enters the Korean market. A SAP consulting portfolio project that localizes a EU-standard ERP to Korean food regulations (Food Traceability Act, 22 allergens, electronic tax invoices, etc.).

**Current status**: Phase 4 is in progress. The Spring Boot backend implements Case intake, inventory lookup, event dispatch and Run scheduling; `mcp-server/` provides a local stdio connector. PostgreSQL has 30 ERP tables plus 13 interface tables. `governance/`, `dashboard/` and the Zig CLI remain scaffolds. Actual LLM execution and approval/write adapters are future work; see `docs/08_interface_overview.md` §13. All business documentation is written in Korean.

## Commands

Use Java 21 and PostgreSQL 18. For the backend, create an empty DB and configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` locally; Flyway applies V1–V17, including the required Orchestrator/channel bootstrap. Use a separate disposable DB for integration tests.

```bash
cd backend
./gradlew clean test bootJar --no-daemon
./gradlew bootRun
# From the repository root in a separate terminal:
cd mcp-server
npm ci
npm test
npm start
```

For standalone schema verification, use another empty DB and apply the DDL in order. Do not apply raw DDL and then run Flyway on the same unbaselined database:

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
psql -d mulino_coreano -f database/ddl/07_case_management.sql
psql -d mulino_coreano -f database/ddl/08_case_indexes.sql
psql -d mulino_coreano -f database/ddl/09_case_fks.sql
psql -d mulino_coreano -f database/seed/interface.sql
psql -d mulino_coreano -f database/seed/allergens.sql
```

**Planned stack** (new code follows this baseline): Backend is Spring Boot 4.1.x + Java 21 + Gradle exposing a REST API. Agents are Claude Code or Codex sessions (Cowork) driven by per-role skills; the planned agent tool surface is a single Zig CLI (`mulino`) calling the REST API. Dashboard is React 19 + Vite. There is no A2A protocol — the runtime's native subagent dispatch (Claude Code or Codex) replaces it. The implemented `mcp-server/` is a local stdio interface connector using the same REST API; remote HTTP transport and the Zig CLI remain future work.

## Architecture (4 layers = directory mapping)

| Layer | Directory | Role |
|---|---|---|
| L0 | `database/`, `backend/` | PostgreSQL 18 (30 ERP + 13 interface tables) + Spring Boot REST API (single entry point for CLI and dashboard) |
| L1 | `governance/` | Intercept action-bearing API calls → approve / block / hold + audit log. **Reads pass through; only writes are gated** |
| L2 | `agents/` | `cli/` (Zig `mulino` binary) + `skills/` (orchestrator / supply-chain / procurement / qc). Claude Code and Codex are both supported agent runtimes; the orchestrator dispatches role subagents. See `agents/CLAUDE.md` |
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

## Git rules

- **No direct commit/push to main** — work on a separate branch, then open a PR to merge (force push is strictly forbidden)
- Commit message prefixes: `feat` | `chore` | `fix` | `docs` (e.g. `feat(migration): create migration files`)

## Conventions
- The issue/PR label scheme is in `docs/06_labels.md` (category + `L0-db`~`L3-dashboard` layer labels)
- Never commit secrets (`application-local.yml`, `.env`) — already in `.gitignore`
- On schema changes, keep `docs/02_flow.md` consistent with the ERD (Phase 1 required "flow diagram–ERD 100% consistency" as an acceptance criterion)

## Issue/PR templates (mandatory)

Templates live in `.github/`. When creating issues or PRs, Claude Code must follow the structure of the relevant template exactly — do not omit sections or invent your own format.

**PR**: `.github/pull_request_template.md` — 4 sections (작업 내용 / 변경 사항 / 체크리스트 / 리뷰 요청 사항). The 3 checklist items (verify local run, do not commit secrets, update related docs) must appear in the PR body.

**Korean PR requirement**: Always create pull requests from `.github/pull_request_template.md`, and write both the PR title and the entire PR body in Korean.

**Issues**: pick one of the following by task type (title prefix and label are auto-applied).
- `bug.md` — `[BUG]` · `bug` — bug report (bug description / reproduction steps / expected & actual behavior / environment)
- `feature.md` — `[FEAT]` · `feature` — feature development (overview / L0–L3 layer checkboxes / detailed tasks / definition of done / references)
- `qc.md` — `[QC]` · `qc` — QC/test (target / items / method / result Pass·Fail)
- `research.md` — `[RESEARCH]` · `research` — upfront research (purpose / items / result + sources / design impact / sources)

Check the `config.yml` contact link (project docs) before creating an issue. Blank issues are allowed, but for types that have a template, using the template is the default.
