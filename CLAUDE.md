# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트 개요

Mulino Bianco(이탈리아 식품 브랜드)의 한국 진출을 가정한 가상 ERP + AI 에이전트 거버넌스 시스템. EU 기준 ERP를 한국 식품 법규(식품이력추적관리법, 알레르겐 22종, 전자세금계산서 등)에 맞게 현지화하는 SAP 컨설팅 포트폴리오 프로젝트.

**현재 상태**: Phase 3까지 완료(기획/ERD/DDL/문서화). `backend/`, `governance/`, `agents/`, `dashboard/`는 빈 스캐폴드 — 아직 실행 가능한 코드 없음. 모든 문서는 한국어로 작성.

## 명령어

빌드/테스트 도구는 아직 없음. 현재 유일한 실행 대상은 DDL:

```bash
# DB 생성 후 FK 의존성 순서대로 실행 (파일 번호 순서 필수)
createdb mulino_coreano
psql -d mulino_coreano -f database/ddl/00_types.sql
psql -d mulino_coreano -f database/ddl/01_master_tables.sql
psql -d mulino_coreano -f database/ddl/02_relation_tables.sql
psql -d mulino_coreano -f database/ddl/03_transaction_tables.sql
psql -d mulino_coreano -f database/ddl/04_indexes.sql
psql -d mulino_coreano -f database/seed/allergens.sql
```

**예정 스택** (새 코드는 이 기준으로): Backend는 Spring Boot 3.x + Java 17 + Gradle, Tool 노출은 MCP Server, 에이전트는 Multi-Agent + A2A, 대시보드는 React.

## 아키텍처 (4레이어 = 디렉터리 매핑)

| 레이어 | 디렉터리 | 역할 |
|---|---|---|
| L0 | `database/`, `backend/` | PostgreSQL 24개 테이블 + Spring Boot + MCP Server (ERP 기능을 Tool로 노출) |
| L1 | `governance/` | 액션성 Tool Call 가로채기 → 승인/차단/보류 + 감사 로그. **조회는 통과, 쓰기만 통제** |
| L2 | `agents/` | Orchestrator / Supply Chain / Procurement / QC (A2A 협업) |
| L3 | `dashboard/` | 자연어 질의 → Intent Parsing → 차트 생성 |

### Governance 승인 매트릭스 (L1 구현 시 준수)

- `INSERT purchase_orders` → MANAGER 승인
- `UPDATE inbound` (차단/보류) → QC 승인
- `INSERT recalls` → ADMIN 승인
- `UPDATE production_lots.status = 'RECALLED'` → ADMIN 승인

### 양방향 LOT 추적 체인 (스키마 변경 시 절대 끊지 말 것)

```
suppliers → purchase_orders → purchase_order_items → inbound
→ raw_material_lots → production_ingredients → production_lots
→ outbound_lots → outbound → orders → customers
```

역추적(원인 파악)은 이 체인의 역방향. 핵심 무결성: `SUM(outbound_lots.lot_quantity) = outbound.quantity`, 생산 투입 시 `raw_material_lots.remaining_quantity` 차감.

### 한국 현지화 불변 조건 (설계의 존재 이유 — 임의 변경 금지)

- 알레르겐 22종 (`allergens` 마스터, EU 14종 아님)
- 전자세금계산서 컬럼 (`tax_invoice_number` / `tax_invoice_date` — purchase_orders, orders)
- 리콜 시 식약처 즉시 보고, 이력 보관 2년
- 인증서: HACCP/GMP/이력추적등록 (만료 30일 전 알림, 만료 시 입고 차단)

전체 흐름과 에이전트 개입 시점은 `docs/02_flow.md`가 단일 진실 원천(SSOT). 테이블 목록·SAP 모듈 매핑은 `docs/01_project_overview.txt` 참조.

## Git 규칙

- **main에 직접 commit/push 금지** — 별도 브랜치에서 작업 후 PR을 생성해 병합 (force push 절대 금지)
- 커밋 메시지 접두어: `feat` | `chore` | `fix` | `docs` (예: `feat(migration): create migration files`)

## 컨벤션
- 이슈/PR 라벨 체계는 `docs/06_labels.md` (카테고리 + `L0-db`~`L3-dashboard` 레이어 라벨)
- 시크릿(`application-local.yml`, `.env`) 커밋 금지 — `.gitignore`에 이미 정의됨
- 스키마 변경 시 `docs/02_flow.md`와 ERD 일치를 유지할 것 (Phase 1에서 "흐름도-ERD 100% 일치"가 검수 기준이었음)

## 이슈/PR 템플릿 (필수)

템플릿은 `.github/`에 정의되어 있음. Claude Code는 이슈/PR 생성 시 반드시 해당 템플릿의 구조를 그대로 따를 것 — 임의로 섹션을 생략하거나 자체 포맷을 만들지 말 것.

**PR**: `.github/pull_request_template.md` — `작업 내용` / `변경 사항` / `체크리스트` / `리뷰 요청 사항` 4섹션. 체크리스트 3항(로컬 실행 확인, 시크릿 파일 커밋 안 함, 관련 문서 업데이트)은 PR 본문에 반드시 포함.

**이슈**: 작업 성격에 따라 아래 중 하나 선택 (title 접두어와 라벨 자동 부착).
- `bug.md` — `[BUG]` · `bug` — 버그 리포트 (버그 내용 / 재현 방법 / 예상·실제 동작 / 환경)
- `feature.md` — `[FEAT]` · `feature` — 기능 개발 (작업 개요 / 레이어 체크박스 L0–L3 / 상세 작업 / 완료 조건 / 참고 자료)
- `qc.md` — `[QC]` · `qc` — QC/테스트 (검증 대상 / 항목 / 방법 / 결과 Pass·Fail)
- `research.md` — `[RESEARCH]` · `research` — 사전조사 (조사 목적 / 항목 / 결과 + 출처 / 설계 반영 사항 / 출처)

이슈 생성 전 `config.yml` contact link(프로젝트 문서)를 먼저 확인. 빈 이슈(blank issue)는 허용되지만 템플릿이 존재하는 유형은 템플릿 사용이 기본.
