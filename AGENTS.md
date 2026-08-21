# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

A hypothetical ERP + AI agent governance system assuming Mulino Bianco (an Italian food brand) enters the Korean market. A SAP consulting portfolio project that localizes a EU-standard ERP to Korean food regulations (Food Traceability Act, 22 allergens, electronic tax invoices, etc.).

**Current status**: Phase 3 complete (planning / ERD / DDL / documentation). `backend/`, `governance/`, `dashboard/` are empty scaffolds; `agents/` holds layout and AGENTS.md guidance only — no runnable code yet. All documentation is written in Korean.

## Commands

No build/test tooling yet. The only runnable target is the DDL:

```bash
# After creating the DB, run in FK-dependency order (file number order is mandatory)
createdb mulino_coreano
psql -d mulino_coreano -f database/ddl/00_types.sql
psql -d mulino_coreano -f database/ddl/01_master_tables.sql
psql -d mulino_coreano -f database/ddl/02_relation_tables.sql
psql -d mulino_coreano -f database/ddl/03_transaction_tables.sql
psql -d mulino_coreano -f database/ddl/04_indexes.sql
psql -d mulino_coreano -f database/ddl/05_foreign_keys.sql
psql -d mulino_coreano -f database/seed/allergens.sql
```

**Planned stack** (new code follows this baseline): Backend is Spring Boot 4.1.x + Java 25 (LTS) + Gradle exposing a REST API. Agents are Codex sessions (Codex / Cowork) driven by per-role skills; their only backend access is a single Zig CLI (`mulino`) that calls the REST API. Dashboard is React 19 + Vite. There is no A2A protocol — Codex's native subagent dispatch replaces it. An MCP Server is deferred pending a complexity-management decision; until then, the CLI is the sole tool surface.

## Architecture (4 layers = directory mapping)

| Layer | Directory | Role |
|---|---|---|
| L0 | `database/`, `backend/` | PostgreSQL 18 (30 tables) + Spring Boot REST API (single entry point for CLI and dashboard) |
| L1 | `governance/` | Intercept action-bearing API calls → approve / block / hold + audit log. **Reads pass through; only writes are gated** |
| L2 | `agents/` | `cli/` (Zig `mulino` binary) + `skills/` (orchestrator / supply-chain / procurement / qc). Codex is the agent runtime; the orchestrator dispatches role subagents. See `agents/AGENTS.md` |
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

Templates live in `.github/`. When creating issues or PRs, Codex must follow the structure of the relevant template exactly — do not omit sections or invent your own format.

**PR**: `.github/pull_request_template.md` — 4 sections (작업 내용 / 변경 사항 / 체크리스트 / 리뷰 요청 사항). The 3 checklist items (verify local run, do not commit secrets, update related docs) must appear in the PR body.

**Korean PR requirement**: Always create pull requests from `.github/pull_request_template.md`, and write both the PR title and the entire PR body in Korean.

**Issues**: pick one of the following by task type (title prefix and label are auto-applied).
- `bug.md` — `[BUG]` · `bug` — bug report (bug description / reproduction steps / expected & actual behavior / environment)
- `feature.md` — `[FEAT]` · `feature` — feature development (overview / L0–L3 layer checkboxes / detailed tasks / definition of done / references)
- `qc.md` — `[QC]` · `qc` — QC/test (target / items / method / result Pass·Fail)
- `research.md` — `[RESEARCH]` · `research` — upfront research (purpose / items / result + sources / design impact / sources)

Check the `config.yml` contact link (project docs) before creating an issue. Blank issues are allowed, but for types that have a template, using the template is the default.
