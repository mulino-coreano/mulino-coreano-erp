# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

A hypothetical ERP + AI agent governance system assuming Mulino Bianco (an Italian food brand) enters the Korean market. A SAP consulting portfolio project that localizes a EU-standard ERP to Korean food regulations (Food Traceability Act, 22 allergens, electronic tax invoices, etc.).

**Current status**: Phase 4 is in progress. The Spring Boot backend implements Case intake, inventory lookup, event dispatch, Run scheduling and Auth0 JWT/ERP-role access control; `mcp-server/` provides authenticated stdio and Streamable HTTP with Auth0 OBO token exchange. Planning services load reconciled ERP snapshots, calculate historical demand/BOM/supplier candidates, and persist immutable scoped plan versions. Case intake queues Runs; leased execution APIs and a Node runner coordinate scoped agents, with a minimal Zig CLI and tested Codex Docker image. Live model execution remains pending. PostgreSQL has 30 ERP tables plus 13 interface, one identity, nine planning, one request-idempotency and one purchase-application table. Real tenant/client login validation remains separate from local tests. `governance/` and `dashboard/` remain scaffolds. The backend purchasing proposal/decision/apply path is implemented with generated jOOQ queries. Actual model execution, conversation decision tools and other write adapters remain pending; see `docs/08_interface_overview.md` §13, `docs/11_auth0_setup.md` and `docs/12_replenishment_calculation.md` and `docs/13_execution_and_plan_api.md`, `docs/14_cli_and_runtime.md`. All business documentation is written in Korean.

## Commands

Use Java 21 and PostgreSQL 18. For the backend, create an empty DB and configure `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MULINO_AUTH_ISSUER` and `MULINO_API_AUDIENCE` locally; Flyway applies V1–V23, including Orchestrator/role bootstrap, external identities, planning data, leases/idempotency latest planning-attempt state and purchasing approval/application contracts. Compilation generates jOOQ types from a disposable PostgreSQL 18 Docker container; Docker is therefore required for a clean backend build. Follow `docs/11_auth0_setup.md` for Auth0/MCP configuration. Use a separate disposable DB for integration tests. The planning fixture must be loaded into an empty disposable business database before adding demo login identities; see `database/seed/replenishment_demo_README.md`.

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
psql -d mulino_coreano -f database/ddl/10_external_identities.sql
psql -d mulino_coreano -f database/ddl/11_planning_data.sql
psql -d mulino_coreano -f database/ddl/12_queued_run_status.sql
psql -d mulino_coreano -f database/ddl/13_execution_and_idempotency.sql
psql -d mulino_coreano -f database/ddl/14_planning_attempt_marker.sql
psql -d mulino_coreano -f database/ddl/15_purchase_approval.sql
psql -d mulino_coreano -f database/seed/interface.sql
psql -d mulino_coreano -f database/seed/allergens.sql
```

**Planned stack** (new code follows this baseline): Backend is Spring Boot 4.1.x + Java 21 + Gradle exposing a REST API. Agents are Claude Code or Codex sessions (Cowork) driven by per-role skills; the planned agent tool surface is a single Zig CLI (`mulino`) calling the REST API. Dashboard is React 19 + Vite. There is no A2A protocol — the runtime's native subagent dispatch (Claude Code or Codex) replaces it. The implemented `mcp-server/` has authenticated stdio and remote HTTP transports using the same REST API; the Zig CLI implements scoped Case/plan/work commands while material/PO commands remain future work.

## Architecture (4 layers = directory mapping)

| Layer | Directory | Role |
|---|---|---|
| L0 | `database/`, `backend/` | PostgreSQL 18 (30 ERP + 13 interface + 1 identity + 9 planning + 1 idempotency + 1 purchase application tables) + Spring Boot REST API (single entry point for CLI and dashboard) |
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
- New purchasing queries use jOOQ generated tables/columns/enums. Keep SQL access in repositories and transaction/business decisions in services. Do not concatenate request values into SQL. Existing JDBC modules are migrated incrementally; do not add another ORM or duplicate hand-maintained schema models.
- `./gradlew generateJooq` applies Flyway migrations in a fresh PostgreSQL 18 Docker container and writes only `backend/build/generated/sources/jooq`. Never generate against a production/application database or commit generated files. `compileJava` runs generation when inputs change. Keep jOOQ schema rendering disabled so the connection search path remains authoritative for isolated tests.
- The issue/PR label scheme is in `docs/06_labels.md` (category + `L0-db`~`L3-dashboard` layer labels)
- Never commit secrets (`application-local.yml`, `.env`) — already in `.gitignore`
- On schema changes, keep `docs/02_flow.md` consistent with the ERD (Phase 1 required "flow diagram–ERD 100% consistency" as an acceptance criterion)

## Issue/PR templates (mandatory)

Templates live in `.github/`. When creating issues or PRs, Codex must follow the structure of the relevant template exactly — do not omit sections or invent your own format.

**PR**: `.github/pull_request_template.md` — 4 sections (작업 내용 / 변경 사항 / 체크리스트 / 리뷰 요청 사항). The 3 checklist items (verify local run, do not commit secrets, update related docs) must appear in the PR body.

**Korean PR requirement**: Always create pull requests from `.github/pull_request_template.md`, and write both the PR title and the entire PR body in Korean.

**Issues**: pick one of the following by task type (title prefix and label are auto-applied).
- `bug.md` — `[BUG]` · `bug` — bug report (bug description / reproduction steps / expected & actual behavior / environment)
- `feature.md` — `[FEAT]` · `feature` — feature development (overview / L0–L3 layer checkboxes / detailed tasks / definition of done / references)
- `qc.md` — `[QC]` · `qc` — QC/test (target / items / method / result Pass·Fail)
- `research.md` — `[RESEARCH]` · `research` — upfront research (purpose / items / result + sources / design impact / sources)

Check the `config.yml` contact link (project docs) before creating an issue. Blank issues are allowed, but for types that have a template, using the template is the default.
