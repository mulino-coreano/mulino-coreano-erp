# MULINO COREANO

> Mulino Bianco 한국 진출 가상 ERP + AI 에이전트 거버넌스 시스템

---

## 프로젝트 한 줄 소개

이탈리아 식품 브랜드 **Mulino Bianco**가 한국에 현지 제조 법인을 설립한다고 가정하고, EU 기준 ERP를 한국 식품 법규에 맞게 현지화(Localization)한 뒤, 그 위에 AI 에이전트를 얹어 구매·공급망·품질 업무를 자동화한 가상 ERP 시스템입니다.

---

## 아키텍처 (4개 레이어)

| 레이어 | 구성 | 역할 |
|---|---|---|
| L0 | PostgreSQL 18(30개 테이블) + Spring Boot + MCP Server | ERP 데이터 및 기능을 Tool로 노출 |
| L1 | Governance Engine | 액션성 Tool Call 가로채기 → 승인/차단/보류 라우팅 + 불변 감사 로그 |
| L2 | Multi-Agent (Claude Code / Codex) | Orchestrator / Supply Chain / Procurement / QC |
| L3 | 자연어 대시보드 | Intent Parsing → 결재 큐/품질 알람/추적 차트 자동 생성 |

---

## 인터페이스 메커니즘

이 시스템은 "챗봇이 붙은 ERP"가 아니라, 인간과 AI 에이전트가 **동일한 Case·Work Item·증거·결정 위에서 여러 채널(ChatGPT/Slack/Email/Dashboard)로 상호작용하는 지속성 있는 비즈니스 조직**을 지향합니다. 개념과 규칙은 docs/08_interface_overview.md, 스키마는 07~09 DDL을 참조하세요.

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

- **DB**: PostgreSQL 18 (30개 테이블, 16종 ENUM, 46개 FK)
- **Backend**: Spring Boot 4.1.x + Java 21 + Gradle
- **Tool 노출**: Single Zig CLI (`mulino`) + MCP Server
- **Agent**: Claude Code / Codex Subagent Architecture (Orchestrator / Supply Chain / Procurement / QC)
- **Frontend**: React 19 + Vite (자연어 대시보드)

---

## 핵심 설계 개념

- **3-Way Match**: 발주 → 입고(HOLD 기본) → 송장 검증 (SAP MM 핵심)
- **Batch Management**: LOT 기반 양방향 추적 (역추적/순추적) 및 FEFO 유통기한 관리
- **Governance Persistence**: 에이전트 액션을 가로채 DB 승인 큐(`governance_actions`) 및 불변 감사 로그(`governance_audit_logs`)로 통제
- **Extensible Architecture**: 다단계 BOM(반제품), 자재 유형(포장재/첨가물), IoT 시계열 파티셔닝(BRIN)
