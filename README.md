# MULINO COREANO

> Mulino Bianco 한국 진출 가상 ERP + AI 에이전트 거버넌스 시스템

---

## 프로젝트 한 줄 소개

이탈리아 식품 브랜드 **Mulino Bianco**가 한국에 현지 제조 법인을 설립한다고 가정하고, EU 기준 ERP를 한국 식품 법규에 맞게 현지화(Localization)한 뒤, 그 위에 AI 에이전트를 얹어 구매·공급망·품질 업무를 자동화한 가상 ERP 시스템입니다.

---

## 아키텍처 (4개 레이어)

| 레이어 | 구성 | 역할 |
|---|---|---|
| L0 | PostgreSQL 18(ERP 30 + 인터페이스 13 + 인증 신원 1) + Spring Boot + MCP Server | ERP 데이터 및 기능을 Tool로 노출 |
| L1 | Governance Engine | 액션성 Tool Call 가로채기 → 승인/차단/보류 라우팅 + 불변 감사 로그 |
| L2 | Multi-Agent (Claude Code / Codex) | Orchestrator / Supply Chain / Procurement / QC |
| L3 | 자연어 대시보드 | Intent Parsing → 결재 큐/품질 알람/추적 차트 자동 생성 |

---

## SAP 모듈 매핑

| 설계 테이블 | 대응 SAP 모듈 | 역할 |
|---|---|---|
| `suppliers`, `supplier_certifications` | SAP MM | 공급업체 마스터 |
| `purchase_orders`, `purchase_order_items` | SAP MM | 구매오더 (`ME21N`) |
| `inbound`, `raw_material_lots` | SAP MM | 입고처리 (`MIGO`) |
| `warehouses`, `stock` | SAP EWM | 창고관리 |
| `production_records`, `production_lots` | SAP PP | 생산오더 |
| `products`, `raw_materials` | SAP MM | 자재/제품 마스터 |
| `orders`, `order_items`, `outbound` | SAP SD | 수주오더 (`VA01`) |
| `customers` | SAP SD | 거래처 마스터 |
| `recalls`, `alert_rules`, `alert_events` | SAP QM | 품질알림 및 검사/알람 관리 |
| `governance_*`, `regulatory_submissions` | SAP GRC | 거버넌스, 리스크, 컴플라이언스 |

---

## As-Is / To-Be (현지화)

| 항목 | As-Is (EU) | To-Be (한국) |
|---|---|---|
| 추적성 법규 | EC No 178/2002 | 식품이력추적관리법 (5일 이내 전송 의무) |
| 알레르겐 표시 | EU 14종 | 한국 22종 (19개 법정군 계층 관리) |
| 인증서 종류 | HACCP/BRC/IFS | HACCP/GMP/이력추적등록 |
| 리콜 보고 | EFSA, 24시간 | 식약처, 즉시 보고 (`regulatory_submissions`) |
| 이력 보관 | 5년 | 소비기한 + 2년 (`v_retention_deadlines`) |
| 세금계산서 | 해당 없음 | 국세청 전자세금계산서 의무 관리 |

---

## 기술 스택

- **DB**: PostgreSQL 18 (ERP 30 + 인터페이스 13 + 인증 신원 1개 테이블)
- **Backend**: Spring Boot 4.1.x + Java 21 + Gradle
- **Tool 노출**: Single Zig CLI (`mulino`) + MCP Server
- **Agent**: Claude Code / Codex Subagent Architecture (Orchestrator / Supply Chain / Procurement / QC)
- **Frontend**: React 19 + Vite (자연어 대시보드)

## 인증 구현과 실행 준비

현재 작업 브랜치는 Auth0 JWT 인증, ERP 사용자 역할 검증, `whoami`, 인증된 stdio·Streamable HTTP MCP와 사용자 위임 토큰 교환(OBO)을 구현합니다. 실제 Auth0 tenant와 ChatGPT·Codex 로그인 연결은 별도 설정 및 시험이 필요합니다. 발주 결정·실제 에이전트 실행·완결 데모는 후속 단계입니다.

Backend는 DB 설정과 Auth0 issuer/audience가 필요하며, stdio MCP는 ERP access token을 요구합니다. 인증을 생략하는 개발용 변경 API는 제공하지 않습니다. 준비 순서는 [Auth0 연결 안내](docs/11_auth0_setup.md), 세부 구현 순서는 [재보충 데모 계획](docs/superpowers/plans/2026-09-05-replenishment-demo.md)을 따릅니다.

---

## 핵심 설계 개념

- **3-Way Match**: 발주 → 입고(HOLD 기본) → 송장 검증 (SAP MM 핵심)
- **Batch Management**: LOT 기반 양방향 추적 (역추적/순추적) 및 FEFO 유통기한 관리
- **Governance Persistence**: 에이전트 액션을 가로채 DB 승인 큐(`governance_actions`) 및 불변 감사 로그(`governance_audit_logs`)로 통제
- **Extensible Architecture**: 다단계 BOM(반제품), 자재 유형(포장재/첨가물), IoT 시계열 파티셔닝(BRIN)
