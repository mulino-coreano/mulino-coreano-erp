# MULINO COREANO — 프로젝트 진행 타임라인

> Mulino Bianco 한국 진출 가상 ERP + AI 에이전트 시스템

---

## Phase 0 — 기획 및 방향 설정  

| 항목 | 결정 사항 |
|---|---|
| 프로젝트 정체성 | 단순 벤치마킹 재현 → "한국 진출 현지화(Localization)" 시나리오로 전환 |
| 시나리오 | Mulino Bianco 한국 진출 가정, EU 기준 ERP를 한국 법규로 현지화 |
| 아키텍처 | L0(ERP REST API) → L1(Governance) → L2(Multi-Agent) → L3(시각화) — MCP는 deferred, CLI가 유일한 Tool Surface |
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

**작업 환경**: PostgreSQL 18 (DB명 mulino_coreano) + pgAdmin / psql

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

## Phase 4 — L0: Spring Boot REST API  

| 작업 | 내용 |
|---|---|
| 프로젝트 셋업 | ✅ **완료 (PR #14)** — Spring Boot 4.1.1 + Java 21 + Gradle + PostgreSQL 18 + Flyway |
| Flyway V1 baseline | **치명 경로 — Entity 매핑의 선행 조건**: `database/ddl/`(00~06, SSOT로 유지)에서 `V1__baseline_schema.sql`을 생성해 `backend/src/main/resources/db/migration/`에 배치. `ddl-auto: validate`가 DB 스키마를 요구하므로 이게 없으면 Entity/Repository 작업 불가. 이후 스키마 변경은 **DDL SSOT + Flyway migration 쌍**으로 관리 |
| DB 계정 / 로컬 셋업 | `mulino_app` 역할 생성 스크립트 + `DB_PASSWORD` 환경 변수 가이드 — 현재 암호 기본값이 없어 첫 실행이 막힘 |
| Entity / Repository | 30개 테이블 매핑 (Flyway V1 완료 후 진행) |
| REST API | 도메인별 CRUD + 비즈니스 로직 (CLI와 대시보드의 단일 진입점) |
| MCP Server | **deferred** — 복잡도 관리 결정 전까지 CLI가 유일한 Tool Surface |

**검증**: Phase 4+ 모든 작업의 로컬 검증은 `./gradlew test` (backend/). 향후 CI 게이트도 Gradle 기반으로 계획한다.

**거버넌스 정책 레이어 개정 반영** (2026-08-30, docs/08 §4·§6):
- 조회 API는 **게이팅 없이 통과하되 저널링** — 세션·시각·대상 레코드의 가벼운 읽기 저널 (진위 검증 + 미탐 리플레이 감사 겸용). Phase 5에서 게이트가 소비한다.
- 액션 API는 등급 판정에 필요한 인스턴스 파라미터(공급사·단가·수량·알레르겐 상태)를 요청 페이로드에 정규화된 형태로 싣는다 — POLICY_APPROVED 봉인 조건이 결정론적으로 검증 가능하려면 L0가 이 데이터를 제공해야 하므로.

---

## Phase 5 — L1: Governance Engine (Graded Autonomy)  

| 작업 | 내용 |
|---|---|
| 읽기 저널 | 조회 통과 + 가벼운 저널링 (세션·시각·대상 레코드) — §4② 승격 인용 진위 검증, §6 미탐 리플레이 감사 |
| 액션 인터셉트 | 액션성 API Call 가로채기 → docs/08 §3 3등급 판정 |
| AUTONOMOUS | 결정론 게이트 허용 + 감사 로그 |
| POLICY_APPROVED | 봉인 조건 4종 결정론 검증 → 충족 시 자동 승인 + 감사, 미충족 시 PRE_APPROVAL |
| PRE_APPROVAL | governance_actions PENDING + **§4① 첨부(인용+해석)** — 승인자가 컨텍스트 있는 결정 |
| 비대칭 승격 | 에이전트는 PRE_APPROVAL로 올리기만 가능, 강등 불가(강등은 P6 사람 몫). 트리거는 순수 인용만, 읽기 저널과 대조 검증 (§4②) |
| 승격 루프 | 승격 사건 로깅 → 에이전트 분석(읽기+제안) → P6 draft PR 비준(사람) → 게이트 집행 (§4③) |
| 감사 로그 | 전체 액션 이력 JSONB 스냅샷 영구 기록 (audit) |

**거버넌스 정책 레이어 (Graded Autonomy)** — PR #13, 2026-08-23
- 3등급 자율 분류: `AUTONOMOUS` / `POLICY_APPROVED` / `PRE_APPROVAL` (blast_radius + reversibility + regulatory_hook 기준)
- 설계 문서: `docs/08_governance_policy_layer.md` (SSOT 재분류표 포함)
- 결정론적 속성 검사기 PoC: `scripts/validate_governance_policy.py` — P1~P7 불변식, generator 기반 수천 개 사례 검증
- 참고 리서치: `docs/09_governance_research.md` (agent-guardrails / regulated-LLM / autonomy-literature)
- **2026-08-30 개정**: 맥락 판단의 역할(첨부·비대칭 승격·승격 루프 — §4), 읽기 저널(§6), 등급별 단계 시행(§7), 운영자 데모(§8). 검사기 주장을 "자기일관성 회귀 테스트"로 정상화, P5 40%를 잠정값으로 명시.

**스키마 확장 (Phase 5 착수 전)**: governance_actions에 action_class / blast_radius / reversibility / regulatory_hook / 첨부(인용+해석) / 승격 사유 필드 추가 + 읽기 저널 테이블 — **별도 Flyway migration PR** (V2+, `database/ddl/` SSOT 동기화 포함) (docs/08 §10-1, §10-2). 스키마 변경이므로 AGENTS.md 규칙(docs/02_flow.md·ERD 정합성 유지)이 이 migration PR에도 적용된다.
**work_items 추가**: 같은 migration PR(V2+)에 work_items 테이블 포함 - created_by/assignee는 users FK. docs/08 §4④ 목표의 일급 시민화. 목표 생성은 AUTONOMOUS(LOW blast), 목표 추구의 쓰기는 게이트 판정의 대상이 아니라 컨텍스트 제공자(§4④ 목표-맹목 분류).

**P7 실구현**: 읽기 저널 기반 시퀀스 속성("승격 인용은 최근 N분 내 이 세션에서 실제로 읽은 레코드") — 시간 의존 검사로 전환 (docs/08 §5).

**P5 삼각측량 조사**: 식약처 연도별 리콜·수입식품 부적합 통계(분자) + 의료 알람 피로 문헌의 승인자 용량 역치 + 시나리오 파라미터 모델링(분모) → 잠정 40%를 시나리오의 함수로 대체. [RESEARCH] 이슈로 추적.

**등급별 단계 시행 (staged rollout, docs/08 §7)**: ① AUTONOMOUS 첫날 시행 → ② AUTONOMOUS 구간 승격 첫날 시행(저위험 무대에서 정밀도 측정) → ③ POLICY_APPROVED 청정 사례 N건 누적 후 → ④ POLICY_APPROVED 구간 승격(PO 캐치) 측정 정밀도 충족 후. 롤아웃 경계 = 결정론 vs 모델 판단 경계.

---

## Phase 6 — L2: Multi-Agent (제안의 기준으로서의 맥락 판단)  

| 에이전트 | 핵심 시나리오 |
|---|---|
| Orchestrator | 요청 분석 → 적합 에이전트 라우팅 |
| Supply Chain | 유통기한 임박 감지(FEFO) / 재고 소진 예측 / LOT 추적 / 보관기한 모니터링 |
| Procurement | 납품 지연 감지 / 자동 재발주(봉인 조건 제안) / 인증서 만료 감지 |
| QC | 알레르겐 위반 감지 / 온도 이탈 알람 대응 / 입고 Hold 처리 / 리콜 & 식약처 보고 |

**맥락 판단의 역할 (docs/08 §4)**: 에이전트는 자율 실행의 근거가 아니라 **제안의 기준**이다.
- 모든 쓰기 제안에 **인용+해석 첨부** — PRE_APPROVAL에 도착하는 승인 요청이 컨텍스트를 갖는다 (§4①)
- 승격 트리거는 **순수 인용만** — 읽기 저널과 게이트가 대조 검증한다 (§4②)
- 승격 루프의 **분석 담당** — 승격 로그에서 패턴 후보 발견, 정책 개정은 draft PR 제안으로만 (§4③)
- **목표(work_items)에 할당되어 추구** - 상시 Mission(스킬 파일)이 아니라 구체적 목표가 에이전트의 구동 원리.
- 사람과 같은 행위자 레벨 - 사람 제안은 인용+해석이 없을 뿐, 같은 쓰기 게이트 통과 (§4⑤)

**빌드 순서 제약 (agents/CLAUDE.md)**: CLI 명령 표면은 Phase 4 REST 엔드포인트에서 파생된다. 엔드포인트가 존재하기 전에 cli/를 구현하지 않는다. 스킬이 CLI 명령을 참조하므로 스킬은 마지막에 확정된다.

**거버넌스 텔레메트리에 대한 에이전트 접근은 읽기+제안으로만** — 성적표(승격 정밀도 로그)를 읽는 것은 안전하다. 성적표를 쓰거나 정책을 고치는 것은 안 된다 (§4③).

---

## Phase 7 — L3: 자연어 대시보드  

| 작업 | 내용 |
|---|---|
| Intent Parsing | 자연어 질의 → 의도 분석 |
| Chart Spec 생성 | 데이터 → 시각화 명세 변환 |
| 렌더링 | React 19 + Vite 기반 대시보드 (거버넌스 큐, 품질 알람 모니터, 이력추적 그래프) |
| 운영자 콘솔 | **빈 수신함 뷰** ("오늘 N건 흘러감, 물어온 건 0건") + 근거가 붙은 단일 캐치 카드 (§8 데모의 운영자 화면) |
| 승격 분석 뷰 | 승격률·승격 정밀도·클러스터링된 승격 패턴 — §4③ 루프의 사람 측 인터페이스 |

**대시보드는 운영자의 하루를 보여준다** (§8): 아키텍처 다이어그램이 아니라 운영자 콘솔. 증명은 "시스템이 똑똑하다"가 아니라 "당신의 하루가 달라졌다".

---

## Phase 8 — 발표 / 포트폴리오 준비  

| 산출물 | 내용 |
|---|---|
| As-Is/To-Be 비교 문서 | EU 기준 → 한국 현지화 분석 |
| SAP 모듈 매핑표 | 설계 테이블 ↔ SAP MM/WM/PP/SD/QM/GRC |
| 데모 시나리오 | **a→b 전환 (§8)**: 수십 건의 조용한 재발주(빈 수신함) → 조건 전부 충족인데 에이전트가 인용과 함께 잡는 단 한 건(LOT-104 온도 이탈). 게이트만으론 못 잡고, 에이전트만으론 믿을 수 없고, 조합만이 검증 가능한 근거로 잡는다 |
| 검증 상태 표기 | 데모·문서의 검증 수준 구분 — 자기일관성(검사기 PASS) / 실측(P5 삼각측량 후) / 운영 관측(단계 시행 후). "5000 사례 PASS"를 안전성 증거로 서술하지 않는다 |
| 발표 자료 | 분석 → 설계 → 구현 사고 과정 |
