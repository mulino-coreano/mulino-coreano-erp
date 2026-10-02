# 인간 재보충 계획 API

#48은 local 프로필의 인간이 계획을 계산·조회하는 범위다.
실행 lease와 에이전트 계획 경로는 #49에서 추가한다.

| API | 권한 | 결과 |
|---|---|---|
| POST /api/v1/cases/{caseRef}/plans | work:write 인간 | 불변 계획 또는 자료 확인 attention |
| GET /api/v1/plans/{planRef} | erp:read 인간 | 저장된 계획과 계산 근거 |

호출자는 X-Mulino-Local-Role 헤더와 POST의 Idempotency-Key를 보낸다.
MANAGER·OPERATOR가 계산할 수 있고 VIEWER는 조회만 가능하다.
기본 프로필에서는 이 경로를 403으로 거부한다. 에이전트 토큰은 아직 없다.

요청은 warehouseId, productIds, 선택적인 horizonDays(1~90)다.
asOf는 서버의 Asia/Seoul 시계가 정한다. 응답은 ref, caseRef, version,
warehouseId, asOf, horizonDays, targetDate, sourceSnapshot, result,
sourceHash, hash, attention을 포함한다.

같은 인간·Case·Idempotency-Key·입력은 원래 응답을 반환한다.
다른 입력으로 같은 키를 쓰면 409다. 새 키는 새 계산 버전을 만든다.
계산은 ERP 재고·LOT·발주 수량을 바꾸지 않는다. 계획과 근거만 저장한다.
자료 오류는 attention을 남기며 사용 가능한 계획으로 취급하지 않는다.

V19는 기존 ERP 단가 두 컬럼을 NUMERIC(15,2)로 일치시킨다.
V20은 계획 테이블·수량 NUMERIC(18,6)·유한값 검사·계획의 선택적
created_by_work_item_id FK를 추가한다. 인간 계산의 해당 FK는 NULL이다.
planning_attempt_sequence와 latest_planning_outcome은 #49 범위다.

MCP는 whoami, get_case, get_plan을 제공한다. 인간 역할 헤더만 보내며
service·capability 헤더를 만들지 않는다. 쓰기·승인 도구는 해당 API와
함께 #52의 다음 단계에서 추가한다.

현재 미입고 예정량은 기존 purchase_orders의 납기일을 사용한다.
발주별 목적 창고와 품목별 납기일은 #50의 구매 데이터 이식 후 적용한다.

## Run lease와 에이전트 계획 (#49)

예약은 QUEUED, 실행기가 청구한 상태는 RUNNING이다. 기존 RUNNING
예약은 V22에서 ABORTED와 감사 Event로 보존한다. V21은 enum 추가만,
V22는 lease·에이전트 seed, V23은 계획 attempt 기록을 담당한다.

local 실행기는 X-Mulino-Local-Service를 보낸다. 설정값은
MULINO_LOCAL_SERVICE_SECRET이며 빈 값은 어떤 실행기도 인증하지 않는다.
인간 역할 헤더 또는 Authorization과 함께 보내면 401이다.

| API | 호출자 |
|---|---|
| POST /api/v1/internal/runs/claim | local ServiceActor |
| POST /api/v1/internal/runs/heartbeat | local ServiceActor + leaseToken |
| POST /api/v1/internal/runs/finish | local ServiceActor + leaseToken |
| POST /api/v1/internal/runs/retry | local ServiceActor + leaseToken |
| POST /api/v1/events, /runs, /dispatch | local ServiceActor |
| POST /api/v1/cases/{ref}/plans | 인간 또는 SUPPLY_CHAIN capability |
| POST /api/v1/agent/work-items | ORCHESTRATOR capability |
| POST /api/v1/agent/work-items/{ref}/transition | 해당 업무의 capability |

에이전트는 Authorization: Bearer 헤더로 짧은 수명의 capability를
보낸다. 계획 POST는 이 헤더가 있을 때만 agent 체인이 맡는다.
헤더가 없으면 #48의 인간 경로를 유지한다. purchase-proposal 경로는
capability 없이는 401이며 실제 제안 구현은 #50에서 추가한다.
기본 프로필의 기존 Event·Run 경로에는 service 헤더를 요구하지 않는다.

만료 lease는 한 번만 재시도한다. 두 번째 실패는 attention과 BLOCKED
업무를 남긴다. SUPPLY_CHAIN의 DONE은 최근 계획 attempt가 READY이고
같은 Case·Work Item의 계획을 가리킬 때만 허용된다. 과거의 성공 계획만으로
현재 실패를 덮지 않는다. 실행기 이미지·실제 모델 실행은 후속 범위다.
