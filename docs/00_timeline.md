# MULINO COREANO — 프로젝트 진행 타임라인

> Mulino Bianco 한국 진출 가상 ERP + AI 에이전트 시스템

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
| v4 | **거버넌스·품질·규제 갭 해소 & 확장성(Scalability)** — 거버넌스 영속화(3개), 품질 알람(2개), 규제 증빙(1개), 자재/제품유형/BOM/플랜트 확장 → **30개 테이블 + 1개 뷰** 확정 |

**최종 확정: 30개 테이블, 46개 외래키 관계**
- 양방향 추적 체인(역추적/순추적) 불변 완성
- 거버넌스 쓰기 요청 인터셉트 및 불변 감사 로그 테이블 영속화
- 입고 품질 상태(HOLD 기본값) 및 품질/온도 알람 이벤트 관리
- 식약처 5일 내 이력 전송 및 소비기한+2년 보관 의무 뷰 구축
- 전체 NOT NULL / UNIQUE / CHECK / FK 정합성 100% 검수 완료

---

## Phase 2 — DDL 작성 (PostgreSQL)  

| 단계 | 상태 |
|---|---|
| ENUM 타입 16종 정의 | 완료 (`database/ddl/00_types.sql`) |
| 테이블 30개 (마스터 7, 관계 2, 트랜잭션/거버넌스/규제 21) | 완료 (`database/ddl/01~03.sql`) |
| 인덱스 (양방향 추적, FEFO, 거버넌스 큐, BRIN 시계열) | 완료 (`database/ddl/04_indexes.sql`) |
| 제약조건(UNIQUE/CHECK/FK) 및 법적 보관 뷰(v_retention_deadlines) | 완료 (`database/ddl/05_foreign_keys.sql`) |
| seed 데이터 (allergens 22종 + 19개 법정분류) | 완료 (`database/seed/allergens.sql`) |

**작업 환경**: PostgreSQL (DB명 mulino_coreano) + pgAdmin / psql

**파일 분리 전략**
```
database/
├── ddl/
│   ├── 00_types.sql
│   ├── 01_master_tables.sql
│   ├── 02_relation_tables.sql
│   ├── 03_transaction_tables.sql
│   ├── 04_indexes.sql
│   └── 05_foreign_keys.sql
└── seed/
    └── allergens.sql
```

---

## Phase 3 — 문서화 및 Git 레포 구축  

```
mulino-coreano-erp/
├── README.md                  (프로젝트 개요 + SAP 모듈 매핑 + As-Is/To-Be)
├── .github/                   (이슈/PR 템플릿)
├── docs/
│   ├── 00_timeline.md         (진행 타임라인)
│   ├── 01_project_overview.txt (기획안 + 구조 설명)
│   ├── 02_flow.md             (추적 흐름도 — 거버넌스/알람/규제 반영 완료)
│   ├── 03_erd.md              (전체 ERD — 30개 테이블, 46개 FK 기준)
│   ├── 04_benchmark.txt       (현지 제조 벤치마크 사례)
│   ├── 06_labels.md           (이슈/PR 라벨 정의)
│   ├── 07_governance_erd_gap_research.md (거버넌스·규제 갭 설계 조사)
│   └── assets/                (ERD 이미지 등)
├── database/                  (DDL 30개 테이블 + seed)
├── backend/                   (L0)
├── governance/                (L1)
├── agents/                    (L2)
└── dashboard/                 (L3)
```

---

## Phase 4 — L0: Spring Boot + MCP Server  

| 작업 | 내용 |
|---|---|
| 프로젝트 셋업 | Spring Boot 4.1.x + Java 21 + Gradle + PostgreSQL 연동 |
| Entity / Repository | 30개 테이블 매핑 |
| REST API | 도메인별 CRUD + 비즈니스 로직 |
| MCP Server | ERP 기능을 Tool로 노출 (조회/액션 Tool 구분) |

---

## Phase 5 — L1: Governance Engine  

| 작업 | 내용 |
|---|---|
| 액션 인터셉트 | 액션성 API Call 가로채기 (조회는 통과) |
| 승인 라우팅 | INSERT purchase_orders→MANAGER / inbound상태변경→QC / recalls→ADMIN |
| 감사 로그 | 전체 액션 이력 JSONB 스냅샷 영구 기록 (audit) |

---

## Phase 6 — L2: Multi-Agent  

| 에이전트 | 핵심 시나리오 |
|---|---|
| Orchestrator | 요청 분석 → 적합 에이전트 라우팅 |
| Supply Chain | 유통기한 임박 감지(FEFO) / 재고 소진 예측 / LOT 추적 / 보관기한 모니터링 |
| Procurement | 납품 지연 감지 / 자동 재발주 / 인증서 만료 감지 |
| QC | 알레르겐 위반 감지 / 온도 이탈 알람 대응 / 입고 Hold 처리 / 리콜 & 식약처 보고 |

---

## Phase 7 — L3: 자연어 대시보드  

| 작업 | 내용 |
|---|---|
| Intent Parsing | 자연어 질의 → 의도 분석 |
| Chart Spec 생성 | 데이터 → 시각화 명세 변환 |
| 렌더링 | React 기반 대시보드 (거버넌스 큐, 품질 알람 모니터, 이력추적 그래프) |

---

## Phase 8 — 발표 / 포트폴리오 준비  

| 산출물 | 내용 |
|---|---|
| As-Is/To-Be 비교 문서 | EU 기준 → 한국 현지화 분석 |
| SAP 모듈 매핑표 | 설계 테이블 ↔ SAP MM/WM/PP/SD/QM/GRC |
| 데모 시나리오 | 에이전트 자동화 실제 시연 흐름 |
| 발표 자료 | 분석 → 설계 → 구현 사고 과정 |
