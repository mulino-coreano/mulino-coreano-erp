# Mulino Coreano — Dispatcher(디스패처) 이벤트 루프 스펙

> 이 문서는 Codex 구현의 **계약(contract)** 이다. `docs/08_interface_overview.md`의 "대기와 디스패처"를 실행 가능한 상태 전이 규칙으로 정형화한 하위 명세다.
> 관련 문서: `08_interface_overview.md`(인터페이스 철학), `02_flow.md`(업무 흐름 SSOT), `03_erd.md`(스키마)
> 구현 대상: `backend` (Spring Boot) — `DispatcherService`, `RunService`, `ContextSnapshotService`와 REST 어댑터.

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

- `POST /api/v1/dispatch` — 관리·테스트용 수동 트리거. 호출 사실을 `DISPATCH_REQUESTED`(`source=MANUAL`)로 항상 기록한다.
- `monitor()`에서 실행 가능한 대기가 발견되면 `DISPATCH_SWEEP_TRIGGERED`(`source=MONITOR`)를 기록하고 재판정한다. 실행 가능한 대기가 없으면 Event를 만들지 않으며, 사전 조회는 행 잠금을 잡지 않는다.

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
| `SCHEDULED_TIME` | `condition_payload.due_at` ≤ now (이벤트 인입과 무관하게 폴링/스케줄러로 충족 가능) |
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
| `READY` | `IN_PROGRESS` | Run 시작 시 (Dispatcher가 아닌 실행 계층의 몫) |
| `DONE`/`CANCELLED` | any | **불변**. 완료된 WI는 Dispatcher가 건드리지 않는다 |
| `IN_PROGRESS` | `WAITING` | 에이전트가 명시적으로 "대기 선언" 시 (Run 안에서) |

---

## 3. 실행 스케줄: 판정 결과의 소비

판정 결과 "실행 대상 WI 목록"은 다음 흐름을 따른다.

```
이벤트 → Dispatcher 판정 → 영향받은 WI 목록
                         └─▶ per-WI: 현 에이전트(runs.execution)로 run 생성 요청
```

- 중복 방지: 같은 WI가 이미 scheduling된 상태면 중복 요청하지 않는다. 애플리케이션은 원자적 생성 결과를 사용하고, DB는 `RUNNING` 상태에 대한 Work Item별 partial unique index로 다른 Run 생산자와의 경합도 차단한다.
- **여러 WI → 여러 에이전트**: WI마다 배정 에이전트가 다르면 각각 독립 Run으로 스케줄한다. 한 에이전트의 여러 WI는 1회 실행으로 묶는 것을 기본으로 하되, 이는 최적화 옵션(기본: WI당 1 Run).
- 실행 자체는 기존 `createRun()` 계약을 재사용하고, `trigger_event_id`에 이벤트를 기록해 "이 Run은 이 이벤트 때문에 시작됐다"를 감사 가능하게 한다.
- Work Item Run은 잠근 WI가 `READY`이고, 사용자 배정이 없으며, 요청한 에이전트가 현재 배정된 활성 에이전트와 일치할 때만 생성한다. 런타임은 `CLAUDE` 또는 `CODEX`만 허용한다.
- 대기 해소 시 에이전트와 사용자 모두 미배정인 WI는 `READY` 상태와 함께 `MISSING_HUMAN_CONTEXT` attention을 생성한다. 사용자에게 직접 배정된 WI는 Run 없이 `READY`가 정상이다.
- 배정 에이전트가 비활성화된 경우에도 수신 Event와 대기 해소·READY 전이는 보존한다. 실행을 예약하지 않고 중복 없는 `MISSING_HUMAN_CONTEXT` attention을 열어 담당 복구를 요청한다.
- 컨텍스트 재구성에 최종 실패한 Run은 `FAILED`로 종료하고 응답의 `failedRuns`에만 포함한다. Dispatcher는 해당 WI에 중복되지 않는 `MATERIAL_EXCEPTION` attention을 열어 운영자에게 복구 필요성을 노출한다.

> **최소 구현 범위**: "이벤트를 받아 → 충족되는 대기 조건을 SATISFIED로 → WI를 READY로 → Run 생성 요청"까지를 하나의 트랜잭션으로 구현한다. Run의 실제 LLM 실행 호출(Claude/Codex)은 기존 스택에 위임.

---

## 4. 컨텍스트 스냅샷 구현 규칙

실제 `businessRef` 인덱스 단계는 구현되어 있다. `ContextSnapshotService`가 Case에 속한 Work Item의 `metadata.businessRef`를 현재 DB 상태에서 읽어 `context_snapshot.business.references`로 구성하고, `RunService`가 모든 Run 생성 경로에서 이 재구성을 실행한다.

### 4-1. `business` 계층 — 구현된 refresh 경로

| 단계 | 시점 | 내용 |
|---|---|---|
| **현재 구현** | Run 생성 시 | 해당 Case의 Work Item이 가진 `metadata.businessRef`를 `references` 배열로 재조회해 저장 |
| **향후 확장** | ERP 조회 capability 확장 시 | `suppliers`/`purchase_orders`/`stock`/`production_lots`의 참조와 핵심 필드를 같은 인덱스에 보강 |
| **Phase 5 이후** | 거버넌스 연동 시 | 승인 매트릭스·정책 참조를 `control` 계층에 포함 |

- **refresh 트리거**: `context_snapshot`은 `RunService.createRun()`과 `RunService.tryCreateRun()`에서 Run 생성 직후 항상 재구성하며 캐시하지 않는다.
- **일관성 경계**: Case 목표와 다섯 컨텍스트 계층은 하나의 PostgreSQL SELECT 문에서 조립되어 같은 statement snapshot을 공유한다.
- **구현 책임**: 비즈니스 참조 조회는 `ContextSnapshotService`, 재시도·저장·실패 감사 처리는 `RunService`가 담당한다.

### 4-2. `control` 계층 — refresh 트리거

- 현재 `{"governance":"see docs/02_flow.md"}` 는 거버넌스 규칙 소스에 대한 참조이며, 아직 데이터 행 기반 정책 인덱스는 아니다.
- **Phase 5 시작 전까지는 유지**(거버넌스 원장이 없으므로), **Phase 5에서 `governance_actions`/승인 매트릭스가 생기면** 해당 테이블의 관련 정책 행 참조로 교체한다.
- trigger: 승인 매트릭스(라우팅 규칙)가 데이터로 존재하는 순간, `control`은 그 데이터의 인덱스를 내려보낸다.

---

## 5. 스냅샷 신선도 정책 (reviewer 요청 ②)

`context_snapshot`은 **스냅샷**이다. 대기→재개 간에 낡을 수 있다.

### 5-1. 원칙: 스냅샷은 "기록"이고, 실행 결정의 근거는 "재구성"이다

- `context_snapshot` JSONB는 **그 Run이 무엇을 보고 실행됐는지**를 감사·디버그용으로 보존하는 **감사 레코드**다.
- **Run이 시작될 때의 판독(reading)은 스냅샷을 그대로 쓰지 않고, 현재 DB 상태로 재구성**한다 (always-refresh).
  - 현재 재구성 소스는 `objective`/`obligation`/`organizational`/`businessRef`/`epistemic`/`control`이다.
- 결과적으로 성공한 `context_snapshot`은 재구성 SELECT가 본 일관된 최신 상태다. 재개된 Run이 이전 스냅샷을 정상 실행 입력으로 재사용하지 않는다.

### 5-2. 재개 시 재구성 규칙 명세

| 항목 | 규칙 |
|---|---|
| Run이 WI에 연결되고 WI가 `WAITING→READY`로 재개 | 새 Run 생성 전에 §4 재구성을 수행 |
| 재구성 실패 시 | Run은 1회 재시도 후 `FAILED`로 기록. 스냅샷에는 마지막 성공 스냅샷 + `stale: true` 플래그를 남겨 차이를 감사 가능하게 |
| 스냅샷 신선도 지표 | `context_snapshot->>'reconstructed_at'` 타임스탬프를 항상 기록. 대시보드에서 "최근 24h 재구성 못한 Run" 집계 가능 |

### 5-3. 현재 구현

- `RunService.createRun()`과 `RunService.tryCreateRun()`은 대상을 해소하고 원자적으로 Run을 삽입한 뒤 `ContextSnapshotService.build(caseRef)`를 항상 호출한다.
- `ContextSnapshotService`는 Case 목표, Work Item 상태, 참여 에이전트, `businessRef`, Evidence를 현재 DB에서 조회하고 `reconstructed_at`과 `stale:false`를 기록한다.
- 재구성은 저장점(savepoint) 기반으로 1회 재시도한다. 모두 실패하면 같은 Case에서 가장 최근에 성공적으로 재구성된 `stale=false` 스냅샷을 Work Item이나 이후 실행 상태와 무관하게 복사해 `stale:true`와 실패 메타데이터를 남기고 Run을 `FAILED`로 종료한다.

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
- **식별자 별칭**: snake_case/camelCase 등 허용된 별칭을 함께 제공하면 값이 모두 일치해야 한다. 상충하는 대기 조건은 매칭하지 않고, 상충하는 의존 이벤트 source는 Event 기록 전에 거부한다.
- **조회 의미**: Case 필터는 직접 scope뿐 아니라 해소된 wait/triggered Run의 간접 연관 Event도 반환하지만, 전역 Event의 `caseRef`를 필터 값으로 재작성하지 않는다.

### 멱등성

Dispatcher 판정은 **결정론적**이어야 하며, 같은 이벤트를 두 번 받아도 같은 최종 상태를 만든다.
- `waiting_conditions`에 `resolved_by_event_id`가 이미 있으면 재판정 후에도 상태를 재변경하지 않는다 (WHERE `resolved_by_event_id IS NULL` 가드).
- 중복 이벤트 방지: 공개 `POST /api/v1/events`는 비어 있지 않은 이벤트 고유 키(예: 이메일 message-id, 워크북 해시)를 `external_ref`로 요구한다. `UNIQUE (event_type, external_ref)`가 중복 insert를 차단하며, 같은 키를 다른 scope/payload에 재사용하면 `409 Conflict`로 거절한다. 내부 합성 이벤트는 Dispatcher가 자체 고유 키를 만든다.

---

## 7. API/엔드포인트 명세 (최소 구현)

| 메서드 | 경로 | 동작 | 상태 |
|---|---|---|---|
| `POST` | `/api/v1/events` | 이벤트 인입 → Dispatcher 판정 → 영향받은 WI/대기조건 갱신 → (Run 생성 요청) | **신규 구현** |
| `POST` | `/api/v1/dispatch` | 명시적 재판정 (관리/테스트) | **신규 구현** |
| `GET` | `/api/v1/events?caseRef=` | Case별 이벤트 내역 (직접 case_id뿐 아니라 wait 해소/Run trigger로 간접 연결된 전역 이벤트 포함) | 신규(가벼움) |
| 기존 | `/api/v1/runs` (`createRun`) | Dispatcher가 실행 요청을 만드는 대상. `trigger_event_id` 활용 | 재사용 |

현재 엔드포인트는 애플리케이션 내부/신뢰 경계용이다. L1 인증·거버넌스 인터셉터가 연결되기 전에는 인터넷 또는 비신뢰 네트워크에 직접 노출하면 안 된다. 승인 Event 자체는 DB의 완료된 인간 결정으로 재검증하지만, 이것이 일반 API 인증을 대신하지는 않는다.

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
| T11 | 수동/모니터 재판정 | 각각 `DISPATCH_REQUESTED`/`DISPATCH_SWEEP_TRIGGERED`와 정확한 source 기록 |
| T12 | Claim과 Evidence가 다른 Case | Event 및 `claim_evidence` 모두 기록하지 않고 거절 |
| T13 | 컨텍스트 재구성 최종 실패 | `scheduledRuns` 제외, `failedRuns` 포함, `MATERIAL_EXCEPTION` attention 생성 |
| T14 | Event 직접 SQL 변조/교차 Case scope | DB 제약으로 UPDATE/DELETE/TRUNCATE 및 불일치 INSERT 거절 |
| T15 | Case 매핑 없는 ERP 승인에 호출자 scope 지정 | 400, Event 기록 없음; scope 없는 승인 ID 라우팅은 정상 |
| T16 | 승인·이메일 조건 또는 의존 이벤트의 별칭 충돌 | 잘못된 대기 해소 및 Event 멱등 키 소비 없음 |
| T17 | 회신 도착 전 담당 에이전트 비활성화 | Event·SATISFIED·READY 보존, Run 없음, 담당 복구 attention |
| T18 | 다중 담당·인간 참여·반증된 Claim이 있는 Case | 컨텍스트에 책임·역할·대기·증거 출처·Claim 상태·지지/반증·결정 범위 보존 |
| T19 | 참여자 중복, 다른 Case의 Decision/Attention | DB 제약으로 거부; 독립 DDL과 Flyway 동일 |

---

## 9. 구현된 순서와 다음 경계

1. Event insert → 조건 판정 → 상태 전이 → Run 기록을 한 트랜잭션으로 구현했다.
2. 여섯 Waiting Condition의 fail-closed matcher와 Run 중복 방지를 구현했다.
3. Run 생성 시 단일 SQL 컨텍스트 재구성, 재시도, 실패 감사를 구현했다.
4. V15에서 교차 Case 참조와 Event 불변성을 DB 수준으로 보강했다.
5. 다음 경계는 실제 LLM executor와 L1 인증/거버넌스 인터셉터다. 이 PR의 `RUNNING` 행은 실행 스케줄 레코드이며, executor가 이를 소비해 `COMPLETED`/`FAILED`로 종결해야 한다.

### PR #18 원래 계획 대조 후 보강

- 컨텍스트의 `obligation`은 Case 내 Work Item 참조 목록을 유지하되 제목·책임자·기한·활성 대기·의존 참조를 포함한다. Run의 `work_item_id`와 결합해 현재 책임과 병렬 업무를 구별한다.
- `organizational`은 에이전트와 인간 참여자의 역할을 포함한다. `epistemic`은 `evidence`, `claims`, `decisions` 배열을 가진 객체이며, 증거 출처/관측 시각, Claim 상태·지지/반증, 인간 결정의 적용 범위를 구분한다. 이전 Run 감사 스냅샷은 수정하지 않는다.
- Claim은 직접 주장 actor뿐 아니라 `asserted_by_run_id`의 Run 참조를 포함한다. 직접 actor가 없고 Run으로만 출처가 기록된 Claim은 해당 Run의 에이전트로 책임을 해소한다.
- V16은 중복 참여자와 교차 Case Decision/Attention을 차단한다. 기존 데이터에 모순이 있으면 명시적인 오류로 마이그레이션을 중단하며 이력을 자동 삭제하지 않는다. 운영자가 원인을 확인하고 정정한 후 다시 적용한다.
- V17은 초기 Orchestrator와 채널 기본값을 등록한다. 기존 비활성 에이전트를 자동 재활성화하지 않는다. ACT 입력 검증과 참조번호 충돌 방지, 명시적 재고 검색, MCP 오류 처리는 인터페이스 계층에서 검증한다.
