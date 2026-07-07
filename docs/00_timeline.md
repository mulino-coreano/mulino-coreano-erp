# MULINO COREANO — 프로젝트 진행 타임라인

> Mulino Bianco 한국 진출 가상 ERP + AI 에이전트 시스템

---

---

## Phase 0 — 기획 및 방향 설정  

| 항목 | 결정 사항 |
|---|---|
| 프로젝트 정체성 | 단순 벤치마킹 재현 → "한국 진출 현지화(Localization)" 시나리오로 전환 |
| 시나리오 | Mulino Bianco 한국 진출 가정, EU 기준 ERP를 한국 법규로 현지화 |
| 아키텍처 | L0(ERP+MCP) → L1(Governance) → L2(Multi-Agent) → L3(시각화) |
| ERP 모듈 범위 | MM / WM / PP / SD / QM + FI(세금계산서 부분), HCM/CO/PM 제외 |
| AI 에이전트 | Supply Chain / Procurement / QC 3종 우선 + Orchestrator |
| 차별화 포인트 | Localization 설계 + Governance + 3-Way Match + Batch Management |

---

## Phase 1 — 데이터 모델 설계 (ERD)  

| 버전 | 내용 |
|---|---|
| v1 | EU 기준 베이스라인 — 19개 테이블 |
| v2 | 구매 모듈(purchase_orders, items) + 수주 모듈(orders, items, customers) 추가 → 24개 |
| v3 | 한국 현지화 — cert_type/allergens/products 현지화, 세금계산서 컬럼, NULL 정합성 전수 교정 |

**최종 확정: 24개 테이블**
- 양방향 추적 체인(역추적/순추적) 완성
- 전체 NOT NULL / UNIQUE / CHECK 정합성 검수 완료
- 흐름도와 ERD 100% 일치 확인

---

## Phase 2 — DDL 작성 (PostgreSQL)  

| 단계 |
|---|
| ENUM 타입 8종 정의 | 
| 테이블 24개 (FK 의존성 순서) |
| 인덱스 (에이전트 조회 패턴 반영) | 
| 제약(UNIQUE/CHECK) 체크리스트 | 
| seed 데이터 (allergens 22종) | 
| pgAdmin/psql 실행 | 

**작업 환경**: PostgreSQL (DB명 mulino_coreano) + pgAdmin / psql

**파일 분리 전략**
```
sql/
├── 00_types.sql
├── 01_tables.sql
├── 02_indexes.sql
├── 03_comments.sql
└── seed/allergens.sql
```

---

## Phase 3 — 문서화 및 Git 레포 구축  

```
mulino-coreano-erp/
├── README.md                  (프로젝트 개요 + SAP 모듈 매핑 + As-Is/To-Be)
├── docs/
│   ├── 01_project_overview.md
│   ├── 02_flow.md             (추적 흐름도 — 작성 완료)
│   ├── 03_erd_changelog.md    (v1→v2→v3 변경 이력)
│   ├── 04_localization.md     (EU→한국 현지화 근거)
│   └── 05_agent_scenarios.md  (에이전트별 시나리오)
├── erd/                       (v1, v2, v3 이미지)
├── sql/                       (DDL + seed)
├── backend/                   (L0)
├── governance/                (L1)
├── agents/                    (L2)
└── dashboard/                 (L3)
```

커밋 컨벤션: feat / fix / refactor / docs / chore

---

## Phase 4 — L0: Spring Boot + MCP Server  

| 작업 | 내용 |
|---|---|
| 프로젝트 셋업 | Spring Boot 3.x + Java 17 + Gradle + PostgreSQL 연동 |
| Entity / Repository | 24개 테이블 매핑 |
| REST API | 도메인별 CRUD + 비즈니스 로직 |
| MCP Server | ERP 기능을 Tool로 노출 (조회/액션 Tool 구분) |

---

## Phase 5 — L1: Governance Engine  

| 작업 | 내용 |
|---|---|
| 액션 인터셉트 | 액션성 Tool Call 가로채기 (조회는 통과) |
| 승인 라우팅 | INSERT purchase_orders→MANAGER / inbound차단→QC / recalls→ADMIN |
| 감사 로그 | 전체 액션 이력 기록 (audit) |

---

## Phase 6 — L2: Multi-Agent  

| 에이전트 | 핵심 시나리오 |
|---|---|
| Orchestrator | 요청 분석 → 적합 에이전트 라우팅 |
| Supply Chain | 유통기한 임박 감지 / 재고 소진 예측 / LOT 추적 |
| Procurement | 납품 지연 감지 / 자동 재발주 / 인증서 만료 감지 |
| QC | 알레르겐 위반 / 온도 이탈 / 리콜 Draft 생성 |

통신: A2A 프로토콜

---

## Phase 7 — L3: 자연어 대시보드  

| 작업 | 내용 |
|---|---|
| Intent Parsing | 자연어 질의 → 의도 분석 |
| Chart Spec 생성 | 데이터 → 시각화 명세 변환 |
| 렌더링 | React 기반 대시보드 |

---

## Phase 8 — 발표 / 포트폴리오 준비  

| 산출물 | 내용 |
|---|---|
| As-Is/To-Be 비교 문서 | EU 기준 → 한국 현지화 분석 |
| SAP 모듈 매핑표 | 설계 테이블 ↔ SAP MM/WM/PP/SD/QM/GRC |
| 데모 시나리오 | 에이전트 자동화 실제 시연 흐름 |
| 발표 자료 | 분석 → 설계 → 구현 사고 과정 |

```
지금       DDL 직접 작성 + PostgreSQL 실행 검증
다음       Git 레포 생성 + 문서 초안
그 다음     L0 Spring Boot 착수
```
