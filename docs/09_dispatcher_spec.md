# Mulino Coreano — Dispatcher(디스패처) 이벤트 루프 스펙

> 이 문서는 Codex 구현의 **계약(contract)** 이다. `docs/08_interface_overview.md`의 "대기와 디스패처"를 실행 가능한 상태 전이 규칙으로 정형화한 하위 명세다.
> 관련 문서: `08_interface_overview.md`(인터페이스 철학), `02_flow.md`(업무 흐름 SSOT), `03_erd.md`(스키마)
> 현재 구현: `backend`의 Dispatcher·Run·컨텍스트·실행 권한 API와 `agents/runner/`의 Node 실행기. 실제 Codex CLI·이미지·모델 실행 인수와 구매 승인·적용은 후속이다. 최신 실행 계약과 검증 결과는 [실행·계획 API 안내](13_execution_and_plan_api.md)를 따른다.

---

## 0. 용어

| 용어 | 정의 |
|---|---|
| **Event** | `events` 테이블의 한 행. 시스템 안에서 일어난 일을 기록한 불변 사실. 모든 외부 입력(채널/이메일/3PL/ERP 상태변경)과 내부 동작(Slack 승인, 이메일 Send)이 Event가 된다. |
| **Work Item (WI)** | Case 안의 미해결 의무. `work_items`의 한 행. 언제나 명시적 책임(배정) 또는 대기 조건을 가진다. |
| **Waiting Condition** | WI가 WAITING일 때 충족을 기다리는 조건. `waiting_conditions`의 한 행. 대기 중 LLM은 살아있지 않다. |
| **Dispatcher** | 이벤트가 들어왔을 때 "어느 WI가 다시 실행 가능(runnable)해졌는지"를 판정하고, **영향받은 에이전트만** 실행을 스케줄/기록하는 컴포넌트. 배정이 연속 실행을 뜻하지 않게 한다. |

**핵심 원칙**: Dispatcher는 "무엇을 실행할지"만 결정한다. 에이전트 실행 자체는 기존 실행 체계(Run)에 위임한다.

---

## 1. 트리거: Dispatcher는 언제 도는가

Dispatcher는 다음 두 경로에서 실행된다.

### 1-A. 이벤트 인입 시 (push)

`POST /api/v1/events`가 Event를 INSERT한 **같은 트랜잭션 안에서** Dispatcher 판정을 실행한다. DB에 직접 INSERT한 행은 감사 사실만 만들 뿐 자동 디스패치를 우회하므로, 이벤트 생산자는 이 서비스 경계를 사용해야 한다.

이벤트 수신 지점(현재/향후):
- 이메일 인바운드 소비자(`SUPPLIER_EMAIL_RECEIVED`, `THIRD_PARTY_STOCK_REPORT_RECEIVED`) — 아직 미구현, 훅 지점만 예약
- Slack 승인 콜백(`CHANGE_REQUEST_APPROVED`) — 아직 미구현
- 인간 Send(`EMAIL_SENT`) — 아직 미구현
- **ERP 상태 변경(`INVENTORY_CHANGED` 등)** — L0에서 최초 구현 대상

### 1-B. 명시적 재실행 (pull)

- `POST /api/v1/dispatch` — 허용된 실행기 서비스의 `worker:dispatch` 권한으로 호출하는 관리·테스트용 트리거. 호출 사실을 `DISPATCH_REQUESTED`(`source=MANUAL`)로 항상 기록한다.
- `GET /api/v1/monitor`는 인증된 조회이며 이벤트·대기·Run을 변경하지 않는다.
- Node 실행기의 기본 5초 poll이 호출하는 `/api/v1/internal/runs/claim`은 lease 복구와 `dispatchScheduledIfActionable()`을 수행한다. 기한 도래나 이미 완료된 의존 업무가 있을 때만 `DISPATCH_SWEEP_TRIGGERED`를 기록한다. 인간 모니터 조회를 재판정 트리거로 사용하지 않는다.

---

## 2. Dispatcher 판정 규칙 (이벤트 → WI 상태 전이)

하나의 이벤트는 여러 WI에 영향을 줄 수 있다. 판정은 **WI 단위**로 독립적으로 수행한다.

### 2-1. 대기 조건 충족 (WAITING → READY)

`waiting_conditions.status = 'ACTIVE'`인 WI에 대해, 해당 조건의 `condition_type`과 `condition_payload`가 이벤트에 의해 충족되는지 판정한다.

| `condition_type` | 충족 판정 |
|---|---|
| `SUPPLIER_REPLY` | 이벤트가 `SUPPLIER_EMAIL_RECEIVED`이고 `payload.supplier_id` 또는 `payload.po_ref`가 `condition_payload`와 일치 |
| `EMAIL_SENT` | 이벤트가 `EMAIL_SENT`이고 `payload.case_id`/`payload.work_item_id`가 일치 |
| `APPROVAL` | 이벤트가 `CHANGE_REQUEST_APPROVED`이고 DB에서 완료된 Attention 또는 승인된 Governance Action의 ID가 조건과 일치. 결정 문자열만으로는 절대 매칭하지 않음 |
| `SCHEDULED_TIME` | offset을 포함한 `condition_payload.dueAt` 또는 기존 `due_at` ≤ now. 실행기의 claim sweep으로도 충족 가능 |
| `EXTERNAL_DATA` | 이벤트가 `THIRD_PARTY_*_RECEIVED`이고 `condition_payload.expected_source`와 일치. 조건에 `event_type`이 있으면 그것도 정확히 일치 |
| `DEPENDENCY_DONE` | 실제 `WORK_ITEM_STATUS_CHANGED`의 대상 또는 수동/모니터 스윕 시 재조회한 `dependent_wi_ref`가 DB에서 `DONE`/`CANCELLED`임 |

충족 시:
1. `waiting_conditions.status` = `SATISFIED`, `resolved_at` = now, **`resolved_by_event_id` = 해당 이벤트 ID** (감사 가능)
2. 소속 `work_items.status` = `READY`, `resolved_at` = NULL (readable한 새 상태 기점)
3. 실행 스케줄 대상에 추가

한 WI에 ACTIVE 대기 조건이 여러 개라면 최소 구현은 보수적인 **AND 의미**를 사용한다. 이벤트와 일치한 조건은 즉시 `SATISFIED`로 기록하되, 다른 ACTIVE 조건이 남아 있는 동안 WI는 `WAITING`을 유지한다. 마지막 ACTIVE 조건이 해소된 시점에만 `READY`와 Run 스케줄로 전이한다.

> `ck_waiting_resolved` 제약은 WAITING이 해소 신호와 결합되어야 함을 보장한다.

### 2-2. 의존/차단 해소 (BLOCKED → READY)

- `work_items.status = 'BLOCKED'`이고 `waiting_conditions`가 없는 경우(의존성 표기 방식에 따라): 이벤트로 `condition_payload.dependent_wi_ref`가 해소되면 `READY`로 전이.
- BLOCKED는 인간/에이전트가 명시적으로 "막혔다"고 선언한 상태를 의미하며, 자동 전이는 **의존성이 명확히 연결된 경우만** 허용한다. 모호하면 `attention_requests`로 인간에게 연다. (**최소 구현에서는 BLOCKED 자동 전이 미지원, 명세만 유지**)

### 2-3. 새 이벤트 → 기존 WI 갱신 (READY/IN_PROGRESS 유지)

- 신규 판정성 정보(새 Evidence, ERP 상태 변경)가 기존 WI와 관련되면, **상태는 유지**하면서 Event를 기록한다.
- payload로 Claim–Evidence 연결을 요청할 때는 `claimId`와 `evidenceRef`를 함께 제공해야 한다. 둘을 DB에서 해소하고 Event scope, Claim, Evidence가 같은 Case인지 검증한 뒤 `claim_evidence`를 같은 트랜잭션에서 `SUPPORTS`(기본) 또는 `REFUTES`로 기록한다. 하나만 있거나 Case가 다르면 Event INSERT 전 거절한다.
- Dispatcher는 상태를 "되돌리지"(예: IN_PROGRESS → READY) 않는다. 되감기는 에이전트의 몫.

### 2-4. 영향 없는 WI

- 조건 불일치 → 상태 변화 없음. 실행 스케줄 대상 아님.

### 2-5. 전이 금지 규칙 (가드)

| FROM | TO | 조건 |
|---|---|---|
| `WAITING` | `READY` | 대기 조건 SATISFIED. 그 외 전이 금지 |
| `READY` | `IN_PROGRESS` | 실행 계층이 QUEUED Run을 성공적으로 claim한 때. 예약 생성만으로 전이하지 않음 |
| `DONE`/`CANCELLED` | any | **불변**. 완료된 WI는 Dispatcher가 건드리지 않는다 |
| `IN_PROGRESS` | `WAITING` | 에이전트가 명시적으로 "대기 선언" 시 (Run 안에서) |

---

## 3. 실행 스케줄: 판정 결과의 소비

판정 결과 "실행 대상 WI 목록"은 다음 흐름을 따른다.

```
이벤트 → Dispatcher 판정 → 영향받은 WI 목록
                         └─▶ per-WI: 현재 배정 에이전트의 QUEUED Run 생성
                             └─▶ worker claim → RUNNING / WI IN_PROGRESS
```

- 중복 방지: 같은 WI에 `QUEUED` 또는 `RUNNING` Run이 있으면 중복 생성하지 않는다. DB의 Work Item별 partial unique index와 worker별 활성 lease 유일성으로 다른 예약·claim 생산자와의 경합도 차단한다.
- **여러 WI → 여러 에이전트**: WI마다 배정 에이전트가 다르면 각각 독립 Run으로 스케줄한다. 한 에이전트의 여러 WI는 1회 실행으로 묶는 것을 기본으로 하되, 이는 최적화 옵션(기본: WI당 1 Run).
- 실행 자체는 기존 `createRun()` 계약을 재사용하고, `trigger_event_id`에 이벤트를 기록해 "이 Run은 이 이벤트 때문에 시작됐다"를 감사 가능하게 한다.
- Work Item Run은 잠근 WI가 `READY`이고, 사용자 배정이 없으며, 요청한 에이전트가 현재 배정된 활성 에이전트와 일치할 때만 생성한다. 예약 API의 런타임 값은 `CLAUDE` 또는 `CODEX`이며, 현재 자동 실행기는 Work Item에 연결된 `CODEX` 예약만 claim한다. Claude 자동 실행 어댑터는 이 데모 범위 밖이다.
- 대기 해소 시 에이전트와 사용자 모두 미배정인 WI는 `READY` 상태와 함께 `MISSING_HUMAN_CONTEXT` attention을 생성한다. 사용자에게 직접 배정된 WI는 Run 없이 `READY`가 정상이다.
- 배정 에이전트가 비활성화된 경우에도 수신 Event와 대기 해소·READY 전이는 보존한다. 실행을 예약하지 않고 중복 없는 `MISSING_HUMAN_CONTEXT` attention을 열어 담당 복구를 요청한다.
- 컨텍스트 재구성에 최종 실패한 Run은 `FAILED`로 종료하고 응답의 `failedRuns`에만 포함한다. Dispatcher는 해당 WI에 중복되지 않는 `MATERIAL_EXCEPTION` attention을 열어 운영자에게 복구 필요성을 노출한다.

> **현재 구현 경계**: Dispatcher는 이벤트·대기 해소·READY·QUEUED 예약을 한 트랜잭션으로 처리한다. 실행 계층은 claim·lease·종료·복구를, Node 실행기는 자식 프로세스 제어를 맡는다. 실제 Codex 모델 호출은 전용 CLI·이미지·로그인 준비 후 별도로 인수한다.

---

## 4. 컨텍스트 스냅샷 구현 규칙

실제 `businessRef` 인덱스 단계는 구현되어 있다. `ContextSnapshotService`가 Case에 속한 Work Item의 `metadata.businessRef`를 현재 DB 상태에서 읽어 `context_snapshot.business.references`로 구성하고, `RunService`가 모든 Run 생성 경로에서 이 재구성을 실행한다.

### 4-1. `business` 계층 — 구현된 refresh 경로

| 단계 | 시점 | 내용 |
|---|---|---|
| **현재 구현** | Run 예약 생성 시 | 해당 Case의 Work Item이 가진 `metadata.businessRef`를 `references` 배열로 재조회해 `context_snapshot`에 저장 |
| **현재 구현** | claim 시 | 현재 Case 맥락을 재구성하고, 기존 계획이 있으면 그 범위의 제품·자재·LOT·발주·공급 조건을 실제 ERP snapshot으로 읽어 `execution_context.currentBusinessFacts`에 추가 |
| **후속 확장** | ERP 조회 capability 확장 시 | 최신 계획 범위와 무관한 범용 리소스 조회·업무별 facts 연결 보강 |
| **Phase 5 이후** | 거버넌스 연동 시 | 승인 매트릭스·정책 참조를 `control` 계층에 포함 |

- **refresh 트리거**: `context_snapshot`은 `RunService.createRun()`과 `RunService.tryCreateRun()`에서 Run 생성 직후 항상 재구성하며 캐시하지 않는다.
- **일관성 경계**: 예약용 Case 목표와 다섯 컨텍스트 계층은 하나의 PostgreSQL SELECT에서 조립한다. claim의 현재 맥락과 ERP 사실은 반복 읽기 트랜잭션에서 함께 구성한다.
- **구현 책임**: 예약용 참조 조회는 `ContextSnapshotService`, 예약 재시도·저장은 `RunService`, claim 시 현재 사실 구성과 별도 실행 snapshot 저장은 `ExecutionContextBuilder`·`RunExecutionService`가 담당한다.

### 4-2. `control` 계층 — refresh 트리거

- 현재 `{"governance":"see docs/02_flow.md"}` 는 거버넌스 규칙 소스에 대한 참조이며, 아직 데이터 행 기반 정책 인덱스는 아니다.
- `governance_actions` 등 저장 구조는 있지만 승인·ERP 변경 어댑터와 정책 인덱스는 아직 연결하지 않았다. 거버넌스 실행 계층을 구현할 때 관련 정책 행 참조로 보강한다.
- trigger: 승인 매트릭스(라우팅 규칙)가 데이터로 존재하는 순간, `control`은 그 데이터의 인덱스를 내려보낸다.

---

## 5. 스냅샷 신선도 정책 (reviewer 요청 ②)

`context_snapshot`은 **스냅샷**이다. 대기→재개 간에 낡을 수 있다.

### 5-1. 원칙: 스냅샷은 "기록"이고, 실행 결정의 근거는 "재구성"이다

- `context_snapshot`은 **예약 당시 맥락**, `execution_context`는 **claim 당시 맥락**을 보존하는 별도의 감사 기록이다.
- claim은 현재 `objective`/`obligation`/`organizational`/`businessRef`/`epistemic`/`control`과 Case 범위를 재구성한다. 기존 계획이 있으면 과거 `latestPlan`과 새로 읽은 `currentBusinessFacts`를 구분한다.
- 새 실행은 이전 snapshot을 덮어쓰거나 과거 ERP 사실을 현재 값으로 표시하지 않는다. 계획의 원본 source snapshot도 불변으로 유지한다.

### 5-2. 재개 시 재구성 규칙 명세

| 항목 | 규칙 |
|---|---|
| Run이 WI에 연결되고 WI가 `WAITING→READY`로 재개 | 새 QUEUED Run 생성 직후 예약 snapshot을 재구성하고, 실제 claim에서 다시 현재 사실을 읽음 |
| 예약 재구성 실패 | 1회 재시도 후 `FAILED`. 마지막 성공 snapshot에 `stale:true`와 실패 이력을 추가한 별도 실패 snapshot 저장 |
| claim 재구성 실패 | 예약 snapshot을 보존하고 Run `FAILED`, WI `BLOCKED`, 인간 Attention 기록. 실패한 사실을 정상 실행 맥락으로 사용하지 않음 |
| 스냅샷 신선도 지표 | 각 `reconstructed_at`을 해당 예약/claim 시점으로 해석. 표시할 때 원본 계획과 현재 사실의 관측 시점을 구분 |

### 5-3. 현재 구현

- `RunService.createRun()`과 `RunService.tryCreateRun()`은 대상을 해소하고 원자적으로 Run을 삽입한 뒤 `ContextSnapshotService.build(caseRef)`를 항상 호출한다.
- `ContextSnapshotService`는 Case 목표, Work Item 상태, 참여 에이전트, `businessRef`, Evidence를 현재 DB에서 조회하고 `reconstructed_at`과 `stale:false`를 기록한다.
- 재구성은 저장점(savepoint) 기반으로 1회 재시도한다. 모두 실패하면 같은 Case에서 가장 최근에 성공적으로 재구성된 `stale=false` 스냅샷을 Work Item이나 이후 실행 상태와 무관하게 복사해 `stale:true`와 실패 메타데이터를 남기고 Run을 `FAILED`로 종료한다.
- claim은 현재 담당·Case·lease를 검사하고 별도 `execution_context`를 저장한다. 완료된 Run을 다시 claim하지 않으며, 만료된 lease의 이전 결과로 상태를 덮어쓰지 않는다.

---

## 6. 감사 경로 (결합 규칙)

Dispatcher의 모든 판정은 감사 가능해야 한다:

```
Event(event_id)
  └─▶ waiting_conditions.resolved_by_event_id = event_id   (§2-1)
  └─▶ runs.trigger_event_id = event_id                     (§3)
  └─▶ payload의 검증된 claim/evidence 쌍 → claim_evidence  (§2-3)
```

- 두 FK는 스키마(V9 `resolved_by_event_id`, `trigger_event_id` nullable)에 이미 예약돼 있고, V11(FK)에서 연결된다.
- **불변성**: Event는 한 번 쓰면 `UPDATE`, `DELETE`, `TRUNCATE`할 수 없다. Event/Run의 `(work_item_id, case_id)`는 composite FK로 같은 Case임을 DB가 강제한다.
- **승인 출처**: 승인 Event는 권위 있는 DB 결정에서 Case/WI와 인간 `actor_type=USER`, `user_id`를 도출한다. 호출자가 보낸 결정 문자열은 권한 근거가 아니다.
- **ERP 리소스 승인 범위**: `CASE`/`WORK_ITEM` 리소스는 DB에서 scope를 해소한다. `PURCHASE_ORDER` 등 Case 매핑이 없는 L1 리소스는 전역 승인 Event로만 수신하고, 각 대기의 승인 ID로 대상을 찾는다. 호출자의 Case/WI 지정이나 Claim/Evidence를 통한 임의 scope 축소는 거부한다.
- **식별자 별칭**: snake_case/camelCase 등 허용된 별칭을 함께 제공하면 값이 모두 일치해야 한다. agent 대기 생성 API는 상충·누락·잘못된 시각을 저장 전에 거부하고 일치하는 별칭은 하나의 표기로 정규화한다. 기존에 저장된 상충 조건은 matcher가 해소하지 않으며, 상충하는 의존 이벤트 source는 Event 기록 전에 거부한다.
- **조회 의미**: Case 필터는 직접 scope뿐 아니라 해소된 wait/triggered Run의 간접 연관 Event도 반환하지만, 전역 Event의 `caseRef`를 필터 값으로 재작성하지 않는다.

### 멱등성

Dispatcher 판정은 **결정론적**이어야 하며, 같은 이벤트를 두 번 받아도 같은 최종 상태를 만든다.
- `waiting_conditions`에 `resolved_by_event_id`가 이미 있으면 재판정 후에도 상태를 재변경하지 않는다 (WHERE `resolved_by_event_id IS NULL` 가드).
- 중복 이벤트 방지: 공개 `POST /api/v1/events`는 비어 있지 않은 이벤트 고유 키(예: 이메일 message-id, 워크북 해시)를 `external_ref`로 요구한다. `UNIQUE (event_type, external_ref)`가 중복 insert를 차단하며, 같은 키를 다른 scope/payload에 재사용하면 `409 Conflict`로 거절한다. 내부 합성 이벤트는 Dispatcher가 자체 고유 키를 만든다.
- Case·계획·agent 업무 쓰기는 `Idempotency-Key`로 원래 응답을 재생한다. 과거 계획 응답의 재생은 최신 계산 성공/실패 판정을 되돌리지 않는다.
- claim은 토큰 원문을 저장하지 않는 발급 예외라 재생하지 않는다. worker별 활성 lease 하나를 강제하고 응답 유실 후 Node 실행기는 60초 기다린다. heartbeat/finish는 기존 terminal receipt를 보존하며, 수동 dispatch는 매 호출의 관리 사실을 기록한다. 모든 관리 경로에 공통 멱등 응답 저장을 적용했다고 해석하지 않는다.

---

## 7. 현재 API/엔드포인트 명세

| 메서드 | 경로 | 동작 | 상태 |
|---|---|---|---|
| `POST` | `/api/v1/events` | 이벤트 인입 → 판정 → 대기/WI 갱신 → QUEUED 예약 | 구현: worker 전용 |
| `POST` | `/api/v1/dispatch` | 명시적 재판정 (관리/테스트) | 구현: worker 전용 |
| `GET` | `/api/v1/events?caseRef=` | 직접·간접 연관 Case 이벤트 조회 | 구현: ERP 조회 권한 |
| `POST` | `/api/v1/runs` (`createRun`) | 실행 예약 생성, `trigger_event_id`로 원인 연결 | 구현: worker 전용 |
| `POST` | `/api/v1/cases` | 인간 접수·초기 WI·QUEUED Run 원자적 생성 또는 같은 목표 재사용 | 구현: 인간 업무 위임 권한·멱등 키 |
| `POST` | `/api/v1/internal/runs/claim`, `/heartbeat`, `/finish`, `/retry` | lease 발급·갱신·종료·통제된 복구 | 구현: 지정 worker M2M |
| `POST` | `/api/v1/agent/work-items`, `/{ref}/transition` | 역할별 자식 업무·완료·대기 저장 | 구현: 현재 Run capability·멱등 키 |

현재 인증 계층은 인간 JWT, 지정 worker M2M, Case·Work Item·역할·lease에 묶인 agent capability를 구분한다. 승인 Event의 DB 결정 검증과 실제 발주 승인·ERP 변경 어댑터는 별개이며, 후자는 아직 미구현이다. 계획 API와 실제 외부 인증·모델 실행의 남은 검증은 [실행·계획 API 안내](13_execution_and_plan_api.md)를 따른다.

### 요청/응답 예시 (POST /api/v1/events)

```jsonc
// 요청
{
  "eventType": "SUPPLIER_EMAIL_RECEIVED",
  "externalRef": "msg-<id>",
  "caseRef": "CASE-1842",          // nullable
  "payload": {
    "supplierId": 3,
    "poRef": "PO-104",
    "claimId": 122,
    "evidenceRef": "EV-91",
    "relation": "SUPPORTS"
  }
}
// 응답 (202)
{
  "eventId": 101,
  "satisfiedWaiting": ["WAIT-83"],
  "readyWorkItems":   ["WI-102"],
  "scheduledRuns":    ["RUN-9183"],
  "failedRuns":       []
}
```

---

## 8. 테스트 계약 (acceptance criteria)

| # | 시나리오 | 기대 결과 |
|---|---|---|
| T1 | `SUPPLIER_EMAIL_RECEIVED` + 일치하는 `SUPPLIER_REPLY` 대기조건 | WI `WAITING → READY`, WAIT `SATISFIED`(resolved_by_event_id 설정), Run 생성됨 |
| T2 | 같은 이벤트 재전송 | 상태 멱등 (재변경 없음, 중복 Run 없음) |
| T3 | 불일치 이벤트 | 대기조건/WI 변화 없음 |
| T4 | `EMAIL_SENT` + 일치 | WAIT `SATISFIED`, 에이전트 Run 스케줄 |
| T5 | 완료된 WI(`DONE`)에 도달한 이벤트 | WI 상태 불변 |
| T6 | 재구성이 실패한 Run | `stale:true` + 마지막 성공 스냅샷 보존, Run `FAILED` 기록 |
| T7 | 스냅샷의 `reconstructed_at` 존재 | 항상 기록됨 |
| T8 | `businessRef` 인덱스 구성 | `context_snapshot.business.references`에 Case의 실제 Work Item 참조가 존재 |
| T9 | Dispatcher 경유 Run에 `trigger_event_id` 기록 | 감사 경로(event→run) 추적 가능 |
| T10 | 권위 ID 없는 승인 문자열 또는 존재하지 않는 의존 WI | Event INSERT/대기 해소 없이 4xx 거절 |
| T11 | 실행기 수동 재판정 / 인간 모니터 조회 | 수동 dispatch는 `DISPATCH_REQUESTED`·MANUAL을 기록하고 모니터 조회는 업무·이벤트·Run을 변경하지 않음. 내부 조건부 sweep은 별도 서비스 테스트로 검증 |
| T12 | Claim과 Evidence가 다른 Case | Event 및 `claim_evidence` 모두 기록하지 않고 거절 |
| T13 | 컨텍스트 재구성 최종 실패 | `scheduledRuns` 제외, `failedRuns` 포함, `MATERIAL_EXCEPTION` attention 생성 |
| T14 | Event 직접 SQL 변조/교차 Case scope | DB 제약으로 UPDATE/DELETE/TRUNCATE 및 불일치 INSERT 거절 |
| T15 | Case 매핑 없는 ERP 승인에 호출자 scope 지정 | 400, Event 기록 없음; scope 없는 승인 ID 라우팅은 정상 |
| T16 | 승인·이메일 조건 또는 의존 이벤트의 별칭 충돌 | 잘못된 대기 해소 및 Event 멱등 키 소비 없음 |
| T17 | 회신 도착 전 담당 에이전트 비활성화 | Event·SATISFIED·READY 보존, Run 없음, 담당 복구 attention |
| T18 | 다중 담당·인간 참여·반증된 Claim이 있는 Case | 컨텍스트에 책임·역할·대기·증거 출처·Claim 상태·지지/반증·결정 범위 보존 |
| T19 | 참여자 중복, 다른 Case의 Decision/Attention | DB 제약으로 거부; 독립 DDL과 Flyway 동일 |
| T20 | 같은 WI 또는 worker의 동시 claim | 활성 lease 중복 없음; claim 전 QUEUED와 claim 후 RUNNING 구분 |
| T21 | lease 만료·교차 Case/역할·늦은 결과 | 무효 쓰기 거부, 한 번의 만료 재예약, 반복 실패는 Attention; 기존 terminal receipt 보존 |
| T22 | 미래 시각·완료 의존성·여러 대기 | worker sweep에서 필요한 이벤트만 생성하고 모든 조건 충족 후 한 번 재개 |
| T23 | 최신 계산 오류 뒤 과거 READY 응답 재생 | 서버 소유 최신 시도 판정 유지, 공급망 DONE 거부; 원본 계획은 읽기 가능 |

로컬 서버·Node 자식 프로세스 검증과 실제 Auth0/Codex 실행 인수는 구분한다. 최신 자동 검사 결과는 [실행·계획 API 안내](13_execution_and_plan_api.md)에 기록한다.

---

## 9. 구현 이력과 현재 경계

### 초기 Dispatcher 구현 이력 — V15~V17

1. Event insert → 조건 판정 → 상태 전이 → Run 기록을 한 트랜잭션으로 구현했다.
2. 여섯 Waiting Condition의 fail-closed matcher와 Run 중복 방지를 구현했다.
3. Run 생성 시 단일 SQL 컨텍스트 재구성, 재시도, 실패 감사를 구현했다.
4. V15에서 교차 Case 참조와 Event 불변성을 DB 수준으로 보강했다.
5. 당시 `RUNNING` 행은 실제 모델 호출을 뜻하지 않는 예약 기록이었다. V20~V21에서 이 과거 기록을 ABORTED로 보존하고 실제 예약 상태를 QUEUED로 분리했다.

### 현재 실행 연결 — V20~V22

인증된 접수·역할별 업무 API, lease 서버, 현재 사실 재구성, Node 실행기와 최신 계산 결과 검증을 로컬에서 구현했다. 실제 모델은 전용 Codex CLI·이미지·로그인 준비 후 인수하며, 구매 승인·발주 적용과 외부 회신 어댑터는 후속이다. 예약 생성 또는 Run 종료를 상위 품절 방지 목표의 달성으로 표시하지 않는다.

### 과거 PR #18 계획 대조 후 보강 기록

- 컨텍스트의 `obligation`은 Case 내 Work Item 참조 목록을 유지하되 제목·책임자·기한·활성 대기·의존 참조를 포함한다. Run의 `work_item_id`와 결합해 현재 책임과 병렬 업무를 구별한다.
- `organizational`은 에이전트와 인간 참여자의 역할을 포함한다. `epistemic`은 `evidence`, `claims`, `decisions` 배열을 가진 객체이며, 증거 출처/관측 시각, Claim 상태·지지/반증, 인간 결정의 적용 범위를 구분한다. 이전 Run 감사 스냅샷은 수정하지 않는다.
- Claim은 직접 주장 actor뿐 아니라 `asserted_by_run_id`의 Run 참조를 포함한다. 직접 actor가 없고 Run으로만 출처가 기록된 Claim은 해당 Run의 에이전트로 책임을 해소한다.
- V16은 중복 참여자와 교차 Case Decision/Attention을 차단한다. 기존 데이터에 모순이 있으면 명시적인 오류로 마이그레이션을 중단하며 이력을 자동 삭제하지 않는다. 운영자가 원인을 확인하고 정정한 후 다시 적용한다.
- V17은 초기 Orchestrator와 채널 기본값을 등록한다. 기존 비활성 에이전트를 자동 재활성화하지 않는다. ACT 입력 검증과 참조번호 충돌 방지, 명시적 재고 검색, MCP 오류 처리는 인터페이스 계층에서 검증한다.
