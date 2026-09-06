# 국내 생산 재보충 데모 구현 계획

> 상태: 2·3단계 구현 완료, 4단계 로컬 서버·Node 실행기와 최소 Zig CLI·Codex 이미지 구현. 구매 제안·MANAGER 결정·발주 반영 백엔드는 구현했으며 코드 정리 중이다. 구매 CLI와 인간 MCP 도구를 연결했다. 후속 의무, 실제 Auth0/두 클라이언트 로그인·모델 실행과 전체 인수는 미완료 · 2026-09-06
> 아래 검증 단위별로 진행하고 현재 AGENTS.md의 작업·단계·통합 규칙을 따른다. 전체 데모의 완료와 로컬 구현 완료를 구분하며, 최신 검증 결과는 [실행·계획 API 안내](../../13_execution_and_plan_api.md)를 따른다.

**목표:** ChatGPT 또는 Codex에서 맡긴 완제품 보충 목표를 주문 이력·다단계 BOM·공급 조건으로 분석하고, Auth0로 로그인한 MANAGER가 대화에서 승인하면 원재료 발주를 한 번 반영하고 결과를 검증한다.

**구조:** Spring Boot가 데이터·계산·권한·상태 전이·승인·발주 반영을 소유한다. 원격 MCP는 인간의 대화 인터페이스이며, 별도 로컬 실행기가 Codex 실행을 소비한다. 역할별 에이전트는 Zig `mulino` CLI로 허용된 API만 사용한다.

**기술:** Java 21, Spring Boot 4.1.x, PostgreSQL 18, 기존 Node MCP SDK, Auth0, Codex CLI, Zig 0.16.0. SDK·CLI 버전은 구현 시작 시 확인한 버전을 잠그고 두 클라이언트 연결 시험 결과에 기록한다.

**요구사항:** [요구사항 정의서](../../10_requirements.md), [업무 흐름](../../02_flow.md), [디스패처 계약](../../09_dispatcher_spec.md).

## 1. 확정 범위와 기본값

### 사용자와 합의한 선택

- 전체 ERP 완성이 아닌 첫 완결 데모를 구현한다.
- 국내 생산·판매 모델이며 완제품 직접 구매는 구현하지 않는다.
- 주문 이력 기반 수요 추정, 다단계 BOM, 복수 공급처 비교를 포함한다.
- 실제 실행은 Codex 우선이다. Claude 실행 어댑터는 제외한다.
- 사람은 ChatGPT와 Codex 양쪽에서 같은 업무를 조회하고 승인한다.
- 외부 인증 제공자는 Auth0다. 승인 웹 화면과 전체 대시보드는 만들지 않는다.

### 이번 계획의 구현 기본값

- 단일 기업·단일 생산 거점 데모다. 한 거점에서 하나의 활성 재보충 계획 Case를 운영하고 여러 완제품을 그 계획 안에서 합산한다. 요청이 겹치면 기존 Case의 범위 검토·새 계획 버전으로 연결한다. 자원을 공유하는 복수 계획의 동시 최적화는 제외한다.
- 목표 기간은 미지정 시 오늘부터 30일, 안전재고는 평균 수요 7일분이다. 계산 기준 시간대는 Asia/Seoul이며 과거 이력과 미래 목표 기간을 분리한다. 지원 범위는 최대 90일, 사용자가 명시한 기간은 그대로 사용한다.
- 생산은 필요량·착수일·원재료 소요량 산정까지만 수행한다. 실제 생산 실적, 원재료 투입 차감, 완제품 입고, 출고 변경, 품질 판정, 세금계산서 발행과 외부 발주 전송은 제외한다.
- 로컬 PostgreSQL·백엔드·실행기를 사용하고 MCP만 ngrok의 고정 Dev Domain을 통해 HTTPS로 연결한다. 공개 주소는 `MULINO_PUBLIC_ORIGIN` 설정값으로 주입한다. 터널과 실행기가 중지되면 데모가 중지되며 상시 운영 SLA는 주장하지 않는다.
- 미지원 목표와 자료 누락은 구체적 Attention으로 남긴다. 가정으로 값을 만들어 승인 가능한 발주안을 생성하지 않는다.

### 완료의 의미

계산 근거, MANAGER 결정, 생성된 발주·상세, 감사 기록을 연결하여 조회할 수 있고 승인 전후·중복·실패·재시작 시험이 통과해야 한다. 발주 업무는 DONE으로 끝내되, 생산·입고가 남은 품절 방지 Case는 WAITING으로 유지한다.

## 2. 공통 계약

### 인증과 권한

Auth0의 Authorization Code + PKCE(S256), MCP protected-resource metadata, CIMD 클라이언트 등록을 사용한다. Resource Parameter Compatibility Profile과 authorization response issuer를 활성화한다. 초기 연결 시험에서 Auth0 tenant의 CIMD·OBO 지원을 확인하고 미지원이면 인증을 생략하거나 토큰을 우회 전달하지 않고 설정 오류로 보고한다.

- 공개 MCP audience: `${MULINO_PUBLIC_ORIGIN}/mcp`.
- 내부 ERP API audience: `urn:mulino:erp-api`.
- MCP는 사용자 토큰을 검증한 뒤 Auth0 OBO token exchange로 ERP audience 토큰을 얻는다. 백엔드도 서명·issuer·audience·만료·scope를 검증한다. ID token을 API access token으로 사용하지 않는다.
- 인간 신원은 `(issuer, subject)`를 ERP 사용자에 사전 연결한다. 이메일 자동 매칭·자동 권한 부여는 없다. 역할과 활성 여부의 기준은 ERP `users`다. 외부 로그인 사용자에게 로컬 비밀번호를 요구하지 않도록 외부 신원 관계와 비밀번호 제약을 조정한다.
- scope는 `erp:read`, `work:write`, `procurement:decide`, `worker:dispatch`로 나눈다. scope가 있어도 ERP 역할 검증을 통과해야 한다.
- VIEWER는 조회만, OPERATOR와 MANAGER는 목표 접수·일반 업무 답변을 허용한다. 발주 결정은 MANAGER만 허용한다. ADMIN·QC에는 이번 데모의 MANAGER 권한을 암묵적으로 부여하지 않는다.
- 실행기 M2M 자격증명은 사용자 자격증명과 분리한다. 모델에는 M2M 시크릿·DB 자격증명·사용자 토큰을 전달하지 않는다. 실행기가 발급받은 Case·Work Item·역할·lease에 묶인 단기 capability만 `mulino`에 제공한다. 모든 agent 쓰기는 현재 lease와 허용 action을 서버가 재검증한다.
- 미인증 요청은 401, scope/역할 부족은 403, 오래된 버전·멱등 키 충돌은 409로 반환한다. 개발 편의용 무인증 변경 경로는 두지 않는다.

OAuth는 개별 발주에 대한 사람의 확인을 증명하지 않는다. 인간 대화 클라이언트는 승인 의사를 전달하는 신뢰 경계이며, 양쪽 클라이언트에서 결정 도구를 매번 확인하도록 설정하고 실제 UX를 시험한다. 서버는 인간 신원·결정 대상·버전·사유를 기록하며 클라이언트가 제공하지 않는 확인 증거를 있다고 주장하지 않는다.

### 인터페이스 계약과 현재 상태

기존 `/api/v1` 조회 및 Case 응답 필드는 유지하고 필요한 필드를 추가한다. 아래 경로에는 공통 `/api/v1` 접두사가 붙는다. Case·계획·agent 업무 쓰기는 `Idempotency-Key`를 받으며 같은 키·내용은 기존 결과를 반환하고 다른 내용은 409다. Event 인입은 `externalRef`를 사용한다. claim은 비밀값 발급 예외로 원래 토큰을 저장·재생하지 않으며 worker별 활성 lease 제한과 응답 유실 후 60초 대기로 처리한다. heartbeat/finish의 terminal receipt 보존이나 매 호출 사실을 남기는 수동 dispatch를 공통 업무 멱등 저장과 혼동하지 않는다.

| 인터페이스 | 입력/출력 및 책임 | 현재 상태 |
|---|---|---|
| `GET /me` | 인간 사용자 신원·ERP 역할·허용 capability 반환 | 구현 |
| `POST /cases` | optional replenishment={productSkus, warehouseId, targetDate} 해소, 인간 요청자·초기 업무·QUEUED Run 원자적 저장. 같은 목표·범위의 활성 Case만 재사용 | 구현; 잘못되거나 모호한 구조화 범위는 접수 전에 거부 |
| `GET /cases` 확장 | q/productSku/status 검색 및 요약·다음 조치 | q·SKU·status 검색과 요약 구현 |
| `GET /cases/{ref}/overview` | 목표·범위·담당·모든 활성 대기·계획·근거·결정·이력·남은 의무 | 로컬 구현; 실제 채널 전환 시험 후속 |
| `POST /cases/{ref}/plans` | SUPPLY_CHAIN capability로 계산. 인간의 창고·품목·목표일을 지켜 불변 계획·실제 snapshot·버전·hash 저장 | 구현 |
| `GET /plans/{ref}` | ERP 조회 권한으로 저장된 계획·근거·제외 사유 확인 | 구현 |
| `GET /agent/cases/{ref}`, `/agent/plans/{ref}` | 유효한 capability의 같은 Case 맥락·계획 조회 | 구현 |
| `POST /plans/{ref}/purchase-proposal` | procurement가 검증된 계획의 승인 요청 생성. ERP 발주 행은 만들지 않음 | 백엔드·CLI 구현 |
| `GET /approvals/{id}` | 공급처별 발주 내용·총액·근거·요청 이유·version/hash | 백엔드·MCP 구현 |
| `POST /approvals/{id}/decision` | MANAGER의 APPROVE/BLOCK, 대상 version/hash·사유. 승인과 발주 반영 원자적 실행 | 백엔드·MCP 구현; 실제 확인 UX 시험 후속 |
| `POST /attention/{id}/answer` | answer·expectedVersion·THIS_ACTION/THIS_CASE. 구매 승인을 대신할 수 없음 | V24와 답변·재개 구현 |
| `GET /purchase-orders/{id}` | 발주·상세·원 승인·계획·감사 연결 | 백엔드·agent CLI·MCP 구현 |
| `/internal/runs/claim`, `/heartbeat`, `/finish`, `/retry` | 지정 worker M2M의 lease 제어. 공개 MCP에는 노출하지 않음 | 로컬 구현·검증 |
| `/agent/work-items`, `/agent/work-items/{ref}/transition` | scoped 업무 생성·상태 전이·대기 저장. 임의 내부 상태 PATCH 없음 | 구현 |

MCP는 기존 조회·접수 도구와 `whoami`에 인간 대화 도구 6개를 추가한 상태다. `create_case`는 구조화된 범위와 재사용 가능한 요청 키를 전달한다. `get_case`, `get_plan`, `get_approval`, `decide_purchase`, `answer_attention`, `get_purchase_order`와 `list_cases` 확장 검색을 구현했다. 실제 두 클라이언트의 확인 UX와 채널 전환은 후속이다. 사람에게 계산 API나 Run 조작을 직접 요구하지 않는다.

`decide_purchase`는 readOnlyHint=false로 표시하고 명시적 결정·대상 버전·hash를 입력으로 받는다. 도구 결과는 업무 요약과 참조를 제공하고 토큰·lease·모델 로그는 노출하지 않는다. `monitor_status`는 순수 조회로 변경하고 readOnlyHint=true를 사용한다. 재판정은 허용된 worker의 dispatch API로 분리한다. 이는 인증 구현 과정에서 확정한 접근 경계다.

### 데이터와 호환성

- V1–V17은 수정하지 않고 이후 Flyway migration을 추가한다. 독립 DDL과 ERD도 같은 최종 구조로 갱신한다.
- 추가 모델: external_identities, bom_versions/bom_components, supplier_material_terms, planning_policies, replenishment_plans, request_idempotency는 구현했다. purchase_applications와 구매 승인 연결은 V23/DDL15에 추가했다. 계산 상세는 불변 plan JSONB로 보존하며 ERP 엔터티를 복제하지 않는다.
- BOM component는 하위 product 또는 raw material 중 정확히 하나를 참조한다. 제품별 활성 버전·배치 산출량·생산 리드타임과 성분별 소요량을 저장한다. 순환과 겹치는 유효 버전을 거부한다.
- 공급 조건은 원재료별 여러 supplier, 구매단위·기본단위 환산율, 가격·KRW, MOQ·주문 배수, 납기 일수, 유효기간, 필요한 인증 유형을 저장한다. 기존 raw_materials.supplier_id는 기본 공급처로 유지하되 구매 가능 공급처를 제한하는 단일 기준으로 사용하지 않는다.
- 분수 단위 처리를 위해 계산과 DTO는 BigDecimal을 사용하고 관련 구매·입고·LOT·생산 투입·재고·수주·출고 수량은 NUMERIC(18,6)으로 일관되게 확장한다. 기존 양수·잔여량 범위 제약과 FK는 유지한다. 가격 정밀도도 NUMERIC(18,6), 최종 KRW 약정금액은 원 단위 HALF_UP으로 정한다.
- production_lots에 거점 창고 참조를 추가한다. 기존 생산 기록의 창고가 하나일 때만 backfill하고 여러 창고로 해석되는 LOT은 준비 검사에서 보고한다. 추측으로 위치를 선택하지 않는다.
- V23에서 governance_actions에 Case·Work Item·제안 버전·제안 agent 연결을 추가했다. requested_by는 목표를 맡긴 실제 인간으로 유지하고 제안 agent를 별도 기록한다. 생성 전 resource는 REPLENISHMENT_PLAN을 참조하며 가짜 purchase_order_id를 사용하지 않는다.
- V23에서 purchase_applications의 governance_action_id를 UNIQUE로 두어 한 승인에 의한 공급처별 발주 묶음을 한 번만 생성한다.
- V20~V21에 QUEUED·claimed_at·lease owner/hash·lease expiry·attempt와 멱등 응답 저장을 추가했다. 활성 Run 유일성은 QUEUED/RUNNING 전체에 적용하며 worker별 활성 lease도 하나로 제한한다. 과거 RUNNING 예약은 ABORTED로 보존하고 이미 종료되었거나 대기 중인 의무를 강제로 깨우지 않는다.
- V22는 서버 소유 planning_attempt_sequence·latest_planning_outcome·latest_planning_plan_id로 최신 계산 결과를 기록한다. 같은 Case·원본 Work Item의 최신 READY 계획만 완료 근거가 되며, Attention-only 실패나 과거 응답 재생이 이전 성공을 최신 결과로 오인시키지 않는다.

## 3. 계산과 업무 수명주기

### 수요와 BOM 산정

```text
history = 기준일 이전 56일의 CONFIRMED/SHIPPED 주문 수량(order_date 기준)
weekday_forecast[d] = 해당 요일의 일별 주문량 평균
open_orders[d] = 필요일별 확정 주문 수량 - 해당 주문/제품의 출고 수량
demand[d] = max(weekday_forecast[d], open_orders[d])
safety_stock = 일평균 예측 수요 × 7일
finished_need = 기간별 수요 + 종료 시점 안전재고 - 사용 가능한 공급
material_need = 다단계 BOM을 전개한 순소요량 - 사용 가능한 자재 공급
purchase_qty = 주문 배수에 맞춰 올림(max(material_need, MOQ))
```

- 계산 기준일·시간과 이력 데이터 수집 시작일을 저장한다. 이력이 28일 미만이면 자료 부족 Attention을 생성한다. 이력 범위 안의 주문 없는 날만 0으로 포함한다. PENDING/CANCELLED 주문은 제외한다.
- 미출고 확정 주문은 expected_delivery_date로 배치하고 기한 초과는 오늘 수요에 포함한다. 납기 미지정 주문은 임의 날짜를 넣지 않고 Attention을 생성한다. 예측과 확정 주문은 일별 max로 소비 관계를 표현한다.
- 날짜별 순소요량을 계산하고 목표 기간 말에 안전재고를 둔다. 반제품은 DAG 위상 순서에서 모든 상위 수요를 먼저 합산한 뒤 재고를 한 번만 차감한다. BOM 배치 단위로 생산 수량을 올림하고 생산 리드타임을 역산하여 하위 자재 필요일을 정한다.
- 같은 plan 안에서 LOT/예정 입고를 중복 배정하지 않는다. 완제품·반제품은 ACTIVE이며 소비 예정일까지 유효한 LOT의 잔량을 사용한다. 잔량은 생산량에서 출고 LOT 및 하위 생산 사용량을 차감한다. 반제품 사용 이력을 표현하는 production_product_inputs 관계를 추가하되 이번 데모에서는 기존 실적 seed·조회에만 사용한다.
- 제품 LOT 총 잔량과 stock이 일치해야 계산을 진행한다. QUARANTINE/RECALLED, 만료 LOT, RELEASED가 아닌 원재료 입고는 사용 가능 수량에서 제외한다. 출고 LOT 합계나 원재료 잔량이 맞지 않으면 계획을 중단하고 데이터 정합성 Attention을 생성한다.
- 구매 예정량은 ORDERED/PARTIAL의 미입고 수량 중 필요일까지 도착하는 분량만 고려한다. 과거 납기 초과 미입고는 공급으로 간주하지 않는다. 예상 입고를 현재 가용 재고로 표시하지 않는다.
- 공급처는 활성·필요 인증 유효·필요일 도착 조건을 충족한 후보에서 MOQ/배수 반영 총액, 납기, supplier_id 순으로 결정한다. 인증 30일 이내 만료는 검토 정보에 표시한다. 적격 후보가 없으면 JUDGMENT_REQUIRED 또는 MATERIAL_EXCEPTION으로 남긴다. 한 자재를 여러 공급처에 분할하는 최적화는 제외한다.
- 생산 능력은 데모의 고정 리드타임 가정으로 설명한다. 유한 생산능력 최적화나 예측 정확도 보장은 범위 밖이다.

### 실행·승인·종료

```text
ACT 접수 → Orchestrator 실행 예약
→ 목표 해석/필요 맥락 확인
→ Supply Chain: 수요·BOM 계산 및 근거 기록
→ Orchestrator가 Procurement에 구매안 준비 배정
→ 구매안·Attention·APPROVAL 대기 저장, 현재 모델 실행 종료
→ MANAGER의 명시적인 결정
→ 승인: 조건 재검증 + 공급처별 발주 + 감사 + 승인 이벤트 원자적 기록
→ 새 Codex 실행: 생성 결과 설명, 결정론적 결과 검증 요청
→ 발주 업무 DONE, 생산·입고 후속 업무 WAITING
```

- Orchestrator의 역할 분담에는 Codex의 native subagent dispatch를 사용한다. A2A나 별도 다중 에이전트 프레임워크는 도입하지 않는다. 역할 결과와 hand-off는 반드시 Case/Work Item/근거에 저장한다. 공급망 역할에는 소요량 산정 capability만 추가하고 실제 생산 실행 권한은 주지 않는다.
- lease는 60초, heartbeat는 15초, poll은 5초, Codex 실행 제한은 10분이다. 실행기 기본 동시 처리 수는 1이다. DB 잠금·unique index로 여러 실행기가 같은 업무를 claim하지 못하게 한다.
- Node 실행기는 호스트에서 동작하고 각 Codex 실행은 전용 Docker 컨테이너로 격리한다. 이미지에 고정 버전 Codex CLI·Linux용 mulino·역할 스킬을 포함하고 읽기 전용 root filesystem, 임시 작업 디렉터리, 일반 사용자, 제거된 Linux capabilities를 사용한다. 컨테이너에는 전용 Codex 로그인 볼륨과 해당 Run capability만 제공하며 저장소·사용자 홈·Docker socket·Auth0 M2M 시크릿을 mount하지 않는다. native subagent는 같은 제한 안에서 실행한다. 전용 Codex 로그인은 초기 운영 준비 단계에서 한 번 완료한다.
- Codex는 명시적 설정으로 시작하고 `--json`, `--output-schema`로 결과를 반환한다. 인간 MCP 설정을 상속하지 않는다. 컨테이너에서 접근 가능한 내부 API도 모든 agent capability를 검증하며 DB는 worker 네트워크에 공개하지 않는다. 단순 프롬프트 지시를 비밀 격리로 간주하지 않는다.
- lease 만료는 ABORTED로 기록하고 동일 업무를 최대 한 번 자동 재예약한다. 두 번째 실패, 모델 결과 schema 오류, 10분 초과는 Attention으로 전환한다. 네트워크 실패 후 업무 변경 결과가 불명확하면 멱등 키로 기존 결과를 확인한 다음 재개한다.
- APPROVAL WAITING 동안 모델 프로세스를 유지하지 않는다. 승인 전에 읽기·계산·제안의 입력 사실을 다시 조회한다. 수량·가격·공급처·기한·가용 공급·BOM·정책이 바뀌면 409로 원 제안을 EXPIRED 처리하고 새 버전을 준비한다. 자동으로 변경된 내용에 승인 효력을 옮기지 않는다.
- 승인과 발주 묶음 적용은 한 트랜잭션이다. 중간 오류는 전체 rollback, 응답 유실 후 반복 호출은 기존 발주 묶음을 반환한다. 승인 Event나 Attention 답변만 위조해서 ERP 쓰기를 실행할 수 없어야 한다.
- 반려는 BLOCK 결정과 사유를 기록하고 해당 구매 업무를 종결한다. 같은 제안을 자동 재요청하지 않는다. 상위 목표에는 다음 방침이 필요한 Attention을 남긴다. 사람이 재계획을 요청한 경우에만 새 버전을 만든다.
- 결과 검증은 실제 PO·상세·수량·가격·승인·감사 연결을 결정론적으로 비교한다. LLM의 완료 선언만으로 DONE을 만들지 않는다.
- 생산·입고 후속 업무에는 담당과 가장 이른 납기일의 SCHEDULED_TIME 대기를 남긴다. 기한 도래 시 기존 입고 상태를 재조회하고 미입고이면 인간 주의 요청을 한 번 생성한다. 발주 등록만으로 Case를 RESOLVED/CLOSED로 전환하지 않는다.

## 4. 구현 순서와 검증 단위

각 단계는 테스트 추가 → 구현 → 지정 검사 → 리뷰 가능한 커밋 순서로 진행한다. main에 직접 커밋·푸시하지 않는다. 기존 미커밋 문서 변경을 보존하며, 구현 전 문서 기준점을 작업 브랜치에 함께 정리한다.

### 1단계 — Auth0와 두 클라이언트의 최소 인증 연결

**책임 영역:** `backend/.../security/`, `mcp-server/src/auth/`, 원격 transport와 설정 문서.

- [x] Auth0 두 resource와 MCP OBO client, worker M2M client, CIMD/PKCE/resource/issuer 설정을 구성하는 재실행 가능한 설정 스크립트와 비밀 없는 예제를 작성한다.
- [x] external_identities migration, JWT 검증, 인간/서비스 신원 분리, `/me`, 401 metadata challenge를 구현한다. discovery와 JWKS는 표준 라이브러리를 사용한다.
- [x] stdio와 Streamable HTTP에서 같은 tool registry를 사용하게 분리한다. 기존 stdio도 자격증명 검증을 생략하지 않는다.
- [ ] 고정 HTTPS MCP에서 ChatGPT와 Codex로 같은 사전 등록 MANAGER 로그인·`whoami` 호출·토큰 갱신을 실제 검증한다. 계정 미등록, issuer/audience 오류, 만료, 역할 변경, 서비스의 승인 접근 거부를 테스트한다.

**산출 계약:** 검증된 HumanActor/ServiceActor와 공통 authorization 계층. 실제 연결 성공 후 다음 단계의 쓰기를 공개한다.

### 2단계 — 계획 데이터와 재현 가능한 업무 fixture

**책임 영역:** Flyway/DDL, `backend/.../planning/` 모델, `database/seed/`.

- [x] 공통 계약의 BOM·공급조건·정책·plan·수량·거점·반제품 사용 관계 migration을 추가한다. 순환 BOM, 단위 차원 불일치, 중복 활성 버전을 검증한다.
- [x] 56일 주문, 2개 완제품, 공유 반제품, 2단계 이상 BOM, 복수 공급처, 일부 HOLD/만료 LOT, 미입고 PO가 있는 fixture를 만든다. 모든 LOT·stock·출고 수량을 대조한다.
- [x] 계산 기준일을 명시적 LocalDate 입력으로 주입하여 데모 시간을 고정한다. 실제 API의 날짜 공급은 실행 연결 단계의 Clock에서 담당하고 인증용 시계는 변경하지 않는다. 공급처·가격·BOM 등은 seed로 제공하고 마스터 CRUD 화면은 만들지 않는다.
- [x] 빈 DB Flyway 적용과 V17 DB 업그레이드, 독립 DDL 적용을 서로 다른 disposable DB에서 검증한다. 기존 FK·CHECK·감사 불변성이 유지되는지 검사한다.

**산출 계약:** 동일 입력에서 동일 수요/BOM 테스트를 실행할 수 있는 데이터와 버전 참조.

### 3단계 — 결정론적 수요·BOM·구매 계산

**책임 영역:** `backend/.../planning/`의 ForecastService, BomPlanner, SupplierSelectionService, ReplenishmentCalculator, PlanningSnapshotRepository, PlanPersistenceService.

- [x] §3 계산과 실제 ERP snapshot·plan version·source references·제외 사유 저장을 구현한다.
- [x] `POST /cases/{ref}/plans`, `GET /plans/{ref}`를 구현한다. 인간의 창고·품목·목표일, 현재 SUPPLY_CHAIN capability, 거점 활성 Case와 불변 버전 추가를 트랜잭션으로 보장한다.
- [x] 수요 0, 이력 부족, 확정 주문 중복, 공유 반제품, 순환 BOM, 배치/단위 올림, 만료/보류, 미입고 납기 초과, MOQ, 공급처 동률·없음과 데이터 불일치를 테스트한다.
- [x] 멱등 재생·동시 버전 생성·접수/계획 경합·lease 만료 롤백과 최신 계산 실패 뒤 과거 READY 재생을 검증한다.

**산출 계약:** 불변 계획은 ref·caseRef·version·asOf·horizonDays·targetDate·sourceSnapshot·result·sourceHash·hash를 반환하며 result에 예측·생산/자재 필요량·구매 후보·예외를 담는다. 계산 실행은 ERP 수량을 변경하지 않는다. 유효한 snapshot의 계산 자료 부족은 NEEDS_ATTENTION 계획, snapshot 구성 자체의 자료 오류는 계획 없는 Attention·멱등 오류 응답으로 남긴다.

### 4단계 — Case와 Run의 실제 실행 수명주기

**책임 영역:** `backend/.../execution/`, 기존 intake/dispatcher/context 서비스, `agents/runner/`.

- [x] QUEUED/lease migration, 인간 접수의 초기 Run 예약, claim/heartbeat/finish/retry, scoped 업무 전이·대기·예외 API를 구현한다. `FOR UPDATE SKIP LOCKED`와 WI/worker 유일성을 사용한다.
- [x] 예약 businessRef 인덱스를 보존하고 claim에서 현재 Case 맥락과 최신 계획 범위의 제품·자재·LOT·발주·공급 조건을 다시 읽는다. 예약/실행/과거 계획 snapshot을 구분한다. 범용 ERP 리소스 capability 확장은 후속이다.
- [x] Node 실행기에 프로세스 시작·종료·heartbeat·취소·출력 제한·비밀 가림을 구현한다. DB 접근이나 업무 계산은 넣지 않으며 완료·실패·대기는 서버가 검증한다.
- [x] 로컬 PostgreSQL·실제 Node 자식 프로세스·모의 HTTP로 다중 claim, lease 만료·복구, 늦은 결과, 잘못된 모델 결과, 대기 종료/재개, 원래 terminal receipt 보존을 시험한다.
- [ ] 실제 Auth0와 전용 Codex CLI·이미지·모델의 실행 및 native 역할 hand-off를 인수한다. 실제 구매 승인 후 재개는 6단계 승인 경로와 함께 검증한다.

**산출 계약:** 로컬 서버·runner는 예약과 claim된 실행을 구분하고 중복 실행·오래된 상태 쓰기를 통제한다. 실제 Codex 모델 실행 인수는 아직 완료하지 않았다.

### 5단계 — 역할 스킬과 최소 Zig CLI 연결

**책임 영역:** `agents/cli/`, `agents/skills/`, 실행기와 backend agent capability.

- [x] Zig 0.16.0 stdlib로 `case show`, `work create/transition`, `plan calculate/show`를 구현한다. JSON stdout/error stderr와 기존 exit code 계약, 숫자 정밀도·TLS·제한 시간·리다이렉트 차단을 검증한다.
- [x] `material show`, `po propose/show`와 Case 범위의 자재·발주 조회 API를 추가했다. 인간 승인 명령은 agent CLI에 노출하지 않는다.
- [x] 토큰은 환경변수로 받되 실행 결과·로그에 포함하지 않는다. CLI는 자동 재시도하지 않고 호출자의 멱등 키를 전달한다. 인간 승인 명령은 agent CLI에 넣지 않는다.
- [ ] Orchestrator → Supply Chain → Procurement의 역할별 업무·hand-off·승인 대기를 연결한다. 대기 중 역할 실행의 종료와 상위 Case 책임의 지속을 스킬에 구분한다. 역할 변경을 업무 흐름에도 반영한다.
- [ ] 모의 런타임 계약 테스트와 실제 Codex native subagent 실행 시험을 분리한다. 실제 시험은 계산 결과 참조와 승인 요청이 저장되는 것을 확인한다.

**산출 계약:** 모델이 DB나 임의 발주 SQL 없이 원재료 구매 승인안을 준비한다.

### 6단계 — MANAGER 결정과 원자적 발주 반영

**책임 영역:** `backend/.../procurement/`, `backend/.../governance/`.

- [ ] 불변 발주 묶음 제안, governance action, AUTHORITY_REQUIRED Attention, APPROVAL 대기를 한 트랜잭션으로 만든다. 같은 plan version의 제안 중복을 방지한다.
- [ ] 승인 대상은 공급처별 PO 전체를 포함한 정확한 plan 구매 묶음이다. 잠금 순서는 plan → governance action → 정렬된 관련 공급/재고 행으로 통일한다. 조건 재검증 후 PO·상세·decision·audit·purchase_application·Event를 원자적으로 저장한다.
- [ ] `created_by`는 승인 인간으로 기록하고 요청자·제안 agent는 연결 이력에 남긴다. 전자세금계산서 번호·일자는 실제 발행 전 NULL로 두고 가짜 값을 생성하지 않는다.
- [ ] 반려·만료·변경안 재승인, 승인과 반려 경쟁, 응답 유실 후 재호출, 중간 INSERT 실패, 다른 사람이 전달한 actor ID, worker/OPERATOR의 승인 접근을 테스트한다.
- [ ] 원래 payload의 hash만 비교하지 않고 현재 DB 입력도 다시 검증한다. 구매 검증 서비스만 해당 업무를 DONE으로 전환할 수 있게 한다.

**산출 계약:** MANAGER 승인 한 번에 정확한 원재료 PO 묶음이 한 번 생성되고 근거·감사로 추적된다.

#### 6단계 구현 시 확정한 저장 계약

**현재 우선순위 — 코드 정리:** 구매 제안·결정 use case와 데이터 접근을 분리하고 미사용 JPA를 제거했다. 구매 모듈의 조회·저장·계획 로딩·검증 조회는 스키마 생성 기반 jOOQ로 전환했다. 접수·조회·신원·멱등 처리·계획 저장과 ERP 스냅샷·실행·디스패처도 생성된 타입을 쓰는 저장소로 분리했다. 기존 응답·권한·금액·트랜잭션 회귀를 함께 검증한다. 라이브러리 도입만으로 업무 조건·동시성의 정확성이 보장된다고 간주하지 않는다.

- 기존 발주 상세의 quantity/received_quantity는 기본 단위를 유지한다. 구매 수량·구매 단가·구매/기본 단위·환산율·약정 금액을 별도 열에 저장한다. 기본 단가 unit_price만 NUMERIC(24,9)로 확장하여 현재 KG/G·L/ML의 0.001/1/1000 환산을 정확히 보존한다. 제안 단계에서도 무손실 나눗셈과 저장 범위를 검사하며 기본 단가를 반올림하지 않는다.
- 공급처별 PO 하나에 상세별 납기를 저장하고 헤더에는 가장 늦은 납기를 둔다. 가용 공급 계산은 상세 납기를 우선한다. 새 PO의 계획 거점도 저장한다. 기존 행은 새 단위·거점을 추측해서 채우지 않는다.
- 계획에 연결된 새 governance action은 불변 payload·version·hash·Case/WI·요청 인간·제안 agent를 갖는다. 단일 최종 결정과 단일 purchase application을 강제한다. 일반 Attention 답변과 구매 승인은 action FK로 구분한다.
- 구매 승인 전에는 planning source 전체의 변경이 먼저 획득하는 source guard로 신규 행까지 직렬화하고 최신 source와 계산을 비교한다. 잠금 순서는 요청 조정 → source guard → 창고 조정 → WI/Run/Case/Agent → plan → action → 관련 행이다. 원자적 발주 이후에는 이전 source hash 대신 승인한 발주 행과 실제 생성 행을 비교한다.
- APPROVAL 대기는 제안 transaction의 서버 전용 함수가 저장한다. 모델이 임의 APPROVAL 조건을 등록할 수 없다. 이미 저장된 승인 대기 결과는 원래 Run receipt로 확인한다. 구매 후보가 없는 READY 계획에는 가짜 승인이나 PO를 생성하지 않는다.

### 7단계 — 두 대화 클라이언트의 업무 UX

**책임 영역:** Case overview query, MCP tool registry/한국어 응답, 클라이언트 연결 설정.

- [x] overview와 검색을 구현한다. 인간 담당과 에이전트 담당을 모두 표시하고 모든 활성 대기 사유·근거·판단 범위·타임라인을 반환한다.
- [x] MCP 추가 도구를 연결하고 읽기/쓰기 annotation, Auth0 security metadata, 오류·재인증 응답을 맞춘다. 직원에게 WI/Run 명령을 요구하지 않는다.
- [ ] 발주안에는 필요한 인간 권한, 추천 내용, 계산 근거, 미조치 영향과 남은 불확실성을 표시한다. 설명에서 재고 현재값과 예정 공급을 구분한다.
- [ ] ChatGPT에서 접수 → Codex의 새 대화에서 찾기·검토·승인 → ChatGPT에서 결과 확인을 시연한다. 반대 방향도 시험한다. 결정 도구의 확인을 기억하거나 자동 승인하도록 설정하지 않는다.
- [ ] 일반 Attention 답변은 THIS_ACTION/THIS_CASE 범위만 지원하고, 승인 도구 우회가 불가능한지 검사한다. 발주 완료 후 남은 생산·입고 책임과 기한을 보여준다.

**산출 계약:** 사용자 관점에서 목표·근거·결정·현재 상태가 대화 경계를 넘어 이어진다.

### 8단계 — 전체 검증과 인수 문서

- [ ] disposable PostgreSQL 18에서 `./gradlew clean test bootJar --no-daemon`, MCP·runner의 `npm test`, CLI의 `zig build test`와 모의 HTTP smoke test를 실행한다.
- [ ] 통합 테스트는 예측된 정확한 수량·금액·PO 개수를 fixture의 기대값과 비교한다. 모의 모델 테스트와 실제 모델 시험 결과를 별도 표기한다.
- [ ] 승인 전 PO 0건, 승인 후 정확한 묶음, 반복 요청 후 동일 결과, 반려 후 PO 0건, 변경안 재승인, 재시작 후 재개, 오류 시 성공 미표시를 실제 DB로 검증한다.
- [ ] 발주 업무 DONE·상위 Case WAITING·후속 담당/대기 존재를 검사한다. 이미 끝난 업무 재실행과 반려안의 자동 재요청을 막는다.
- [ ] Auth0·터널·DB·backend·runner·CLI·MCP 준비 검사와 실행/종료 가이드를 작성한다. 비밀은 gitignored 환경 설정으로만 제공하고 문서에는 변수명과 발급 절차만 남긴다.
- [ ] 요구사항의 구매 예시를 원재료 기준으로 수정하고 구현 현황·흐름도·ERD·디스패처·MCP/CLI 문서를 갱신한다. 기능/UX별 실제 시험 근거를 연결한다.
- [ ] 각 검증 단위별 PR은 저장소의 한국어 PR 템플릿 4개 섹션과 3개 체크 항목을 사용한다. 계획 밖 품질·생산·출고 기능을 함께 구현하지 않는다.

## 5. 완료 판정과 준비 항목

| 검증 묶음 | 요구사항 연결 | 완료 증거 |
|---|---|---|
| 목표 접수·검색·상세 | FR-01~03, FR-10, UX-01~04 | 질문으로 Case 미생성, 목표로 접수, 새 대화의 동일 Case 해소 |
| 수요·소요량·발주안 | FR-04~05, FR-11, UX-07~08 | 날짜·단위·BOM·공급처까지 설명 가능한 동일 계산 결과 |
| 인증·승인·반영 | FR-06~07, NFR-02·04 | 올바른 MANAGER만 결정, 정확한 버전과 단 한 번의 원자적 반영 |
| 실행·대기·복구·검증 | FR-08~09·12, NFR-01·03·05 | 모델 실행 종료/재개, lease 복구, 결정론적 업무 완료 |
| 대화 UX | UX-05~06·12 및 제한된 FR-17 | 근거 있는 판단 요청, 승인 후 진행 명령 불필요, 장기 목표 상태의 정직한 표시 |

필요한 외부 값은 Auth0 tenant issuer·MCP OBO client 설정·worker M2M 설정, 사전 연결할 사용자 subject, ngrok 계정의 고정 HTTPS origin, 로컬 Codex 인증과 실행 모델 설정이다. 값 자체는 구현자가 정하는 설계 선택이 아니며 환경별로 주입한다. 연결 기능이 실제 tenant에서 사용 가능한지 1단계에서 확인한다. 이 계획 작성 시 계정 생성·과금·배포·로그인 설정 변경은 수행하지 않았다.

배포 순서는 실행기 중지 → DB migration → backend/MCP 배포 → 인증·읽기 smoke → 실행기 시작 → 새 Case 하나로 승인/반려 시험이다. 오류 시 실행기와 쓰기 진입점을 중지한다. 이미 생성한 발주나 감사 이력을 삭제해 되돌리지 않고 실패·중단 상태로 보존한다.

### 공개 참고자료

- [OpenAI MCP 인증](https://developers.openai.com/plugins/build/auth): OAuth 발견, PKCE, 클라이언트 등록과 token 검증.
- [Auth0 MCP OBO](https://auth0.com/ai/docs/mcp/get-started/call-your-apis-on-users-behalf): MCP audience에서 내부 API audience로 사용자 권한을 유지하는 token exchange.
- [Auth0 resource 호환 설정](https://auth0.com/ai/docs/mcp/guides/resource-param-compatibility-profile): MCP resource 파라미터 처리.
- [Spring Security Resource Server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html): 백엔드 JWT 검증.
- [Codex MCP 설정](https://learn.chatgpt.com/docs/extend/mcp?surface=cli), [ChatGPT Developer mode](https://developers.openai.com/api/docs/guides/developer-mode): OAuth 연결과 도구 확인 설정.
- [ngrok 고정 Dev Domain](https://ngrok.com/docs/gateway/domains): 로컬 MCP의 고정 HTTPS 주소.

## 6. 구현 진행 기록

### 과거 검증 기준점 — 인증 및 V19 계산 코어

- 브랜치: `feat/replenishment-demo-auth0`. 요구사항과 계획 기준점은 `6395e23`에 보존했다.
- 1단계 코드: JWT·ERP 역할 검증, external identity V18/DDL10, `/me`, 인증된 stdio·HTTP MCP, OBO 교환, Auth0 준비·설정 도구를 구현했다.
- 접근 경계 변경: 인간 모니터는 읽기 전용이며 이벤트·Run 예약·dispatch는 허용된 worker 서비스만 호출한다.
- 검증은 실제 PostgreSQL 18 통합 테스트와 서명 JWT/JWKS·token 교환 fixture를 사용한다. 실제 Auth0·ChatGPT·Codex 로그인/갱신은 별도로 기록한다.
- 로컬 검증 결과: Backend `clean test bootJar` 208개, MCP `npm ci` 후 `npm test` 14개, Auth0 설정 스크립트 테스트 18개 통과. 독립 DDL 00~10과 seed 적용·V18/DDL10 일치도 확인했다. 실패·skip은 없으며 실제 tenant 호출은 포함하지 않는다.
- 아직 실제 tenant issuer·OBO 자격증명·고정 공개 주소가 설정되지 않아 1단계 전체 완료로 표시하지 않는다.
- 2단계 V19/DDL11·fixture 구현과 3단계 서버 내부 계산 코어·ERP 스냅샷을 추가했다. 실제 fixture 통합 시험에서 생산 50/30 CASE·반죽 15 KG·구매 후보 16,500원이 확인됐고 ERP 거래는 변경되지 않았다.
- V19 계산 코어 당시 검증: Backend `clean test bootJar` 328개, MCP 15개 통과. 숫자 정밀도·날짜별 BOM·입고/LOT 수량 조작 회귀를 포함했다. 이 수치는 현재 최종 검사 수가 아니다.

### 계획·실행 API 기준점 — 3단계 및 4단계 로컬 연결

- 3단계 계획 API·불변 버전·실제 source snapshot·정밀 hash와 V22 최신 계산 결과를 구현했다. 인간의 구조화된 범위를 강제하고 자료 부족·정합성 오류를 구분한다.
- 4단계의 인증된 멱등 접수·초기 QUEUED 예약·활성 창고 Case 재사용, 역할별 업무 API, lease·복구·대기·완료 검증과 Node 실행기를 구현했다. claim credential은 저장·재생하지 않으며 일반 업무 쓰기의 멱등성과 구분한다.
- 최신 Backend 전체·Node runner·MCP 검증 수와 실행 범위는 [실행·계획 API 안내](../../13_execution_and_plan_api.md)를 따른다. 이력에 남긴 과거 검사 수를 최신 값으로 재사용하지 않는다.
- 실제 Auth0/ChatGPT/Codex 로그인, 실제 Codex용 CLI·이미지·모델 실행 인수, 구매 제안·MANAGER 결정·ERP 발주 적용과 전체 시연은 남아 있다. 5단계 이후의 예정 작업을 이 기준점의 완료로 표시하지 않는다.

### 5단계 현재 진행 — CLI와 실행 이미지

- 최소 5개 CLI 경로, ARM64/AMD64 정적 Linux 빌드, Codex 0.151.0 고정 이미지를 추가했다. CLI 6개 단위·24개 독립 HTTP 시험이 통과했다.
- 실제 ARM64 Docker에서 파일·권한·환경·CA·CLI·설정·취소를 검증했다. 중첩 bwrap의 namespace 오류 때문에 Docker를 보안 경계로 유지하고 내부 Codex의 sandbox만 조정했다. 호스트 권한·Docker privileged 설정은 바꾸지 않았다.
- Orchestrator·Supply Chain 지침의 빠진 명령·영속 대기·최종 JSON을 시나리오 시험으로 확인하고 수정했다. 참고 지침 시험이며 실제 모델 업무 수행 인수는 아니다.
- 서버 맥락과 Node 전달 경로의 소수·큰 정수 정밀도를 보존한다. 실제 예시 계획 claim 크기도 현재 제한 안에서 확인했다.
- [CLI·런타임 안내](../../14_cli_and_runtime.md)에 재현 방법과 검증 범위를 기록했다. 실제 로그인·native subagent 업무 수행, 구매 명령·승인·반영은 계속 남아 있다.


### 인간 대화 도구와 일반 답변의 로컬 연결

- Case overview/search와 MCP 조회·구매 결정·일반 답변 도구를 연결했다. 실제 Auth0/두 클라이언트 확인 절차가 완료됐다는 뜻은 아니다.
- V24/DDL16은 Attention의 질문 변경과 답변 버전을 관리한다. 일반 답변은 구매 승인을 대체하지 않으며, 다른 대기·주의 요청·실행이 없는 해당 BLOCKED 업무만 재개한다.
- 답변의 Decision.sourceAttentionId 연결을 agent 맥락과 인간 overview에서 보존한다. 이것은 `decisions.metadata`에 기록되는 참조이며 신규 ERP 테이블이 아니다.
- 생산·입고 후속 의무를 생성·추적하는 기능과 실제 모델·클라이언트 전체 시연이 남아 있어 7·8단계 전체를 완료로 표시하지 않는다. 최신 검사 결과는 실행·계획 API 안내를 따른다.
