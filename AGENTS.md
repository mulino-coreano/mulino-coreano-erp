# AGENTS.md

This file is the operating guide for agent sessions working in this repository. Claude Code and Codex both read `AGENTS.md`, so it is the only instruction file — there is no `CLAUDE.md`, and directory-scoped guidance lives in a nested `AGENTS.md`.

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

## 작업은 보드에서 온다

이 저장소의 목표는 전부 GitHub 에 있다. 보드는 [Mulino Coreano — ERP & Agent Governance](https://github.com/orgs/mulino-coreano/projects/1) 하나이며, 운영 규칙은 `docs/06_labels.md` 에 있고 그쪽이 우선한다.

- **목표 단위는 마일스톤(Phase)과 이슈다.** 문서에만 적힌 목표는 추적되지 않는 목표다.
- **세션을 시작하면 보드부터 확인한다.** `.agents/skills/goals/board.sh` 로 현재 Phase 와 목표를, `.agents/skills/backlog/next-issue.sh` 로 다음 작업 후보를 본다. 이 파일이나 `docs/` 의 진행 상태 서술보다 보드가 최신이다.
- **이슈 없는 작업은 시작하지 않는다.** 대응하는 이슈가 없으면 멈추고 사람에게 묻는다 — 이슈를 임의로 만들어 진행하지 않는다. 질문·조사·오타 수정은 예외다.
- **현재 Phase 밖의 일을 자발적으로 시작하지 않는다.** 필요해 보이면 제안하고, 판단은 사람이 한다.
- **보드 Status 는 손대지 않는다.** 이슈·PR 상태에서 자동으로 정해진다.

작업 수행 절차는 `backlog` 스킬, 목표 추가·조정 대화는 `goals` 스킬 (둘 다 `.agents/skills/`).

## Git rules

- **No direct commit/push to main** — work on a separate branch, then open a PR to merge (force push is strictly forbidden)
- Commit message prefixes: `feat` | `chore` | `fix` | `docs` (e.g. `feat(migration): create migration files`)

## Conventions
- The issue/PR label scheme is in `docs/06_labels.md` (category + `L0-db`~`L3-dashboard` layer labels)
- Never commit secrets (`application-local.yml`, `.env`) — already in `.gitignore`
- On schema changes, keep `docs/02_flow.md` consistent with the ERD (Phase 1 required "flow diagram–ERD 100% consistency" as an acceptance criterion)
- Dev-workflow skills (`backlog` for carrying out an issue, `goals` for changing what the goals are — not ERP roles) live in `.agents/skills/`, which both Claude Code and Codex discover natively. ERP role skills stay in `agents/skills/` with the existing `.claude/` and `.codex/` symlinks.

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
