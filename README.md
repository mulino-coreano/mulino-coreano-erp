# MULINO COREANO

> Mulino Bianco 한국 진출 가상 ERP + AI 에이전트 거버넌스 시스템
> SAP STAR 인턴 채용(개발 트랙 / 2027 사이클) 포트폴리오

---

## 프로젝트 한 줄 소개

이탈리아 식품 브랜드 **Mulino Bianco**가 한국에 현지 제조 법인을 설립한다고 가정하고, EU 기준 ERP를 한국 식품 법규에 맞게 현지화(Localization)한 뒤, 그 위에 AI 에이전트를 얹어 구매·공급망·품질 업무를 자동화한 가상 ERP 시스템입니다.

---

## 프로젝트 배경

해외 식품 브랜드가 한국 시장에 진출할 때 널리 쓰이는 방식은 "핵심 원료·레시피는 본사가 통제하고, 완제품은 현지 공장에서 제조"하는 구조입니다. 대표적으로 **코카콜라**는 미국 본사가 원액을 공급하고 한국 보틀링 파트너가 여주·양산 등 현지 공장에서 완제품을 생산·유통합니다.

주목할 점은 코카콜라 본사뿐 아니라 현지 보틀링 파트너(제조사)들도 **SAP S/4HANA**를 운영한다는 것입니다. 즉 "해외 브랜드의 현지 제조 법인"이야말로 SAP의 전형적인 고객입니다. Mulino Coreano는 바로 이 지점 — Mulino Bianco의 한국 현지 제조 법인 — 을 위한 ERP를 SAP 모듈 구조에 대응하여 설계했습니다.

---

## 아키텍처 (4개 레이어)

| 레이어 | 구성 | 역할 |
|---|---|---|
| L0 | PostgreSQL(24개 테이블) + Spring Boot + MCP Server | ERP 데이터 및 기능을 Tool로 노출 |
| L1 | Governance Engine | 액션성 Tool Call 가로채기 → 승인/차단/보류 라우팅 + 감사 로그 |
| L2 | Multi-Agent (A2A) | Orchestrator / Supply Chain / Procurement / QC |
| L3 | 자연어 대시보드 | Intent Parsing → 차트 자동 생성 |

---

## SAP 모듈 매핑

| 설계 테이블 | 대응 SAP 모듈 |
|---|---|
| suppliers, supplier_certifications | SAP MM (공급업체 마스터) |
| purchase_orders, purchase_order_items | SAP MM (구매오더 ME21N) |
| inbound, raw_material_lots | SAP MM (입고처리 MIGO) |
| warehouses, stock | SAP EWM (창고관리) |
| production_records, production_lots | SAP PP (생산오더) |
| orders, order_items, outbound | SAP SD (수주오더 VA01) |
| recalls | SAP QM (품질알림) |
| Governance Engine | SAP GRC (거버넌스/컴플라이언스) |

---

## As-Is / To-Be (현지화)

| 항목 | As-Is (EU) | To-Be (한국) |
|---|---|---|
| 추적성 법규 | EC No 178/2002 | 식품이력추적관리법 |
| 알레르겐 표시 | EU 14종 | 한국 22종 |
| 인증서 종류 | HACCP/BRC/IFS | HACCP/GMP/이력추적등록 |
| 리콜 보고 | EFSA, 24시간 | 식약처, 즉시 |
| 이력 보관 | 5년 | 2년 |
| 세금계산서 | 해당 없음 | 전자세금계산서 의무 |

---

## 기술 스택

- **DB**: PostgreSQL
- **Backend**: Spring Boot 3.x + Java 17 + Gradle
- **Tool 노출**: MCP (Model Context Protocol) Server
- **Agent**: Multi-Agent + A2A 프로토콜
- **Frontend**: React (대시보드)

---

## 핵심 설계 개념

- **3-Way Match**: 발주 → 입고 → 송장 검증 (SAP MM 핵심)
- **Batch Management**: LOT 기반 양방향 추적 (역추적/순추적)
- **Governance**: 에이전트 액션을 권한(role)에 따라 통제

---

## 프로젝트 구조

```
mulino-coreano-erp/
├── docs/           프로젝트 문서 (기획안, 흐름도, 현지화 근거)
├── database/       DDL + seed 데이터
├── backend/        L0: Spring Boot + MCP
├── governance/     L1: Governance Engine
├── agents/         L2: Multi-Agent
└── dashboard/      L3: 대시보드
```

---

## 진행 현황

| Phase | 내용 | 상태 |
|---|---|---|
| 0 | 기획 / 방향 설정 | 완료 |
| 1 | ERD 설계 (24개 테이블) | 완료 |
| 2 | PostgreSQL DDL 작성 | 진행 중 |
| 3 | Git 레포 + 문서화 | 진행 중 |
| 4~7 | L0 ~ L3 구현 | 예정 |
| 8 | 발표 / 포트폴리오 준비 | 예정 |
