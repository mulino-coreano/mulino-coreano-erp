# 재보충 후속 책임 연결

상태: 로컬 구현·통합 검사 완료. 기준은 첫 데모 계획의 발주 업무 DONE·Case WAITING·기한 확인 요구사항이다. 생산·입고 쓰기와 Case 자동 종결은 추가하지 않는다.

## 저장 계약

V25/DDL17에 `replenishment_followups`를 추가한다. Case·계획·원본 Procurement 업무·후속 업무·검증된 부모 업무를 같은 Case의 typed FK로 연결한다. APPLIED에는 원 purchase_application을 연결하고 NO_PURCHASE_REQUIRED에는 가짜 application이나 납기를 만들지 않는다. 계획·후속 업무·application은 각각 중복 생성되지 않는다. 신원 연결은 수정·삭제로 다른 책임으로 바꾸지 않는다.

후속 업무의 담당은 ORCHESTRATOR이며 서버가 입고 확인을 수행한다. 모델용 Run을 생성하는 업무와 구분한다. 원본 업무의 부모 참조는 실제 같은 Case의 Orchestrator 업무인지 검증해 저장한다. 일반 metadata만으로 후속 권한이나 완료 근거를 만들 수 없다.

## 생성과 검사

검증된 Procurement DONE 트랜잭션 안에서 후속 책임을 저장한 뒤 종료 이벤트를 보낸다. 후속 저장이 실패하면 DONE도 롤백한다. Case는 WAITING으로 남긴다.

APPLIED는 해당 application의 실제 발주 상세·입고·LOT을 조회한다. 날짜만 있는 납기는 Asia/Seoul의 다음 날 00:00을 확인 시점으로 사용한다. 가장 이른 미충족 상세의 시각을 TIMESTAMPTZ와 전용 SCHEDULED_TIME에 저장한다. 만기가 남은 다른 상세를 먼저 미입고로 판정하지 않는다. 레거시 Work Item의 timezone 없는 due_at을 판정 기준으로 사용하지 않는다.

기한이 지난 미충족 상세가 있으면 대기를 유지하여 다음 sweep에서 늦은 입고도 확인한다. 실제 PO 항목·자재·공급처·창고·수량·입고 상태와 LOT 연결을 대조한다. 부분·보류·차단·불일치 데이터를 정상 입고로 간주하지 않는다. 변경 없는 관찰은 새 이벤트나 Attention을 만들지 않는다.

모든 입고가 확인되면 입고 타이머만 종료하고 생산/재고 확인 책임은 유지한다. NO_PURCHASE_REQUIRED도 원 계획의 생산 필요 여부를 반영한 확인 책임을 남긴다. 생산이 필요하지 않은 계획에 생산 작업을 지어내지 않는다. 후속 Attention은 계획당 한 번 생성하며 이미 기록된 인간 답변을 덮어쓰지 않는다. 이후 변화는 관찰과 남은 의무로 표시한다. 입고만으로 품절 해소나 Case 완료를 선언하지 않는다.

## 내부 연결 계약

`followup.ReplenishmentFollowupService`:

- `ensureForVerifiedCompletion(long caseId, long sourceWorkItemId)`: 기존 트랜잭션 필수, 검증된 원본 업무로 책임 생성/재사용.
- `sweepDue()`: caller 트랜잭션에 참여, 실제 변화로 생성한 이벤트 ID 목록 반환. 변경 없음은 빈 목록. 명시적 수동 dispatch의 자체 감사 이벤트는 유지한다.

`followup.ReplenishmentFollowupRepository`:

- `isManagedWork(long workItemId)`
- `hasResponsibility(long parentWorkItemId, long caseId)`
- `forCase(long caseId)`: ref, caseRef, planRef, sourceWorkItemRef, workItemRef, parentWorkItemRef, agentKey, observationStatus, dueAt, attentionRequestId, attentionStatus, observedAt, observation.

Dispatcher의 scheduled/manual sweep이 후속 검사를 호출한다. 일반 후보 쿼리는 typed 후속 업무의 대기를 제외한다. Run 생성·claim 및 일반 Attention 답변도 전용 업무를 일반 모델 실행으로 바꾸지 않는다. Orchestrator 완료 검증은 typed 부모 연결의 미완료 책임을 확인한다.

Case overview·실행 맥락은 `followups`로 이 상태를 보여준다. Monitor의 후속 기한 초과 판단도 전용 TIMESTAMPTZ를 사용한다. 역할 지침은 서버가 만든 책임을 확인하고 원본 조정 업무를 끝내되 Case 전체 완료로 확대하지 않는다.

## 검증 묶음

1. 정상 발주·구매 불필요·중복 완료·생성 실패 롤백.
2. 실제 납기 경계, 복수 상세 납기, 부분/보류/불일치 및 늦은 입고.
3. 반복/동시 sweep에서 Attention·이벤트 중복 없음, 인간 답변 보존.
4. 일반 dispatch·Run 생성·claim·질문 답변의 전용 업무 우회 차단.
5. 부모 재개 후 책임 확인, Case WAITING 및 API/MCP 표시.
6. 통합 Backend·MCP·runner 검사와 갱신한 역할 이미지 검사. 실제 로그인/모델 인수는 별도 기록.


## 검증 기록

Backend 전체 504개와 MCP 26개 검사를 통과했다. 입고/LOT 분할 수량, 복수 납기, 반복·동시 sweep, 늦은 입고 후 인간 답변 보존, 부모 의존 대기의 실제 자동 재개, 일반 경로 우회 차단과 구매 불필요 경로를 포함한다. V25/DDL17 및 18개 독립 DDL·시드 설치를 확인했다.

관찰 상태의 실제 값은 AWAITING_RECEIPT, RECEIPT_REVIEW_REQUIRED, PRODUCTION_REVIEW_REQUIRED, STOCK_REVIEW_REQUIRED다. 리뷰에서 발견한 MCP 상태명 불일치를 고쳤다. 물리적인 생산/입고 쓰기와 Case 자동 종결은 범위 밖이며, 실제 클라이언트·모델 전체 인수는 별도다.
