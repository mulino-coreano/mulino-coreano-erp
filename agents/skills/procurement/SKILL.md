---
name: procurement
description: Use when a Mulino PROCUREMENT Run receives a persisted replenishment plan, resumes after a human purchasing decision, or needs to assess supplier and delivery concerns within its Case.
---

# Procurement

먼저 [공통 실행 계약](../runtime.md)을 읽는다. 구매 역할은 저장된 계획으로 구매안을 제안하고 인간 승인 후 실제 발주를 확인한다. 업무 기준은 `docs/02_flow.md` STEP 1, 3, 4다.

## 설치된 명령과 범위

- `mulino case show <caseRef>` / `mulino plan show <planRef>`: 현재 의무·계획·구매 상태 확인.
- `mulino material show <id>`: 현재 Case 최신 계획에 관련된 원재료와 현재 공급·거래조건·인증 조회.
- `mulino po show <id>`: 현재 Case에 적용된 발주 또는 저장된 계획의 실제 발주 행 근거로 범위가 확인된 PO 조회.
- `mulino po propose <planRef> --json '{}' --request-key '<workItemRef>:purchase:<planRef>'`: 계획 기반 구매 제안. 수량·공급처·가격을 임의 JSON으로 덮어쓰지 않는다.

`po create`, `supplier`, `cert`, 인간 승인·적용 명령은 설치되어 있지 않다. 다른 역할의 토큰을 사용하지 않는다.

Case를 읽은 뒤 현재 `workItemRef`의 `purchasing` 항목이 있으면 먼저 아래 재개 절차로 분기한다. 이미 승인·적용된 원래 계획을 Case의 새 계획으로 대체하여 판단하지 않는다.

## 최초 구매 제안

1. Case를 읽고 `obligation`에서 현재 Work Item의 description에 배정된 계획 참조와 `latestPlan`을 대조한다. `plan show`로 저장된 최신 READY 계획인지 확인한다. 잘못된 범위·누락 자료·오래된 계획은 원인을 설명하고 FAILED로 보고한다. 공급망 계산을 직접 다시 실행하지 않는다.
2. 계획의 선택 자재·공급처·구매량·금액·납기를 확인한다. 필요한 자재는 `material show`로 현재 거래조건과 인증을 확인한다. 현재 사실과 계획이 충돌하면 임의 보정하지 않고 검토가 필요한 차이를 보고한다. 수량·날짜를 만들어 넣지 않는다.
3. `case.purchasing`에서 현재 `workItemRef`의 기존 구매 상태를 먼저 확인한다. 이미 존재하는 승인 요청을 새 키나 새 업무로 재발행하지 않는다. 새 제안이면 위의 빈 JSON과 안정적인 요청 키로 `po propose`를 호출한다.
4. `PENDING_APPROVAL` 응답의 `approvalId`, `version`, `proposalHash`, `planRef`는 저장된 승인 근거다. 응답의 **`executionResult` 객체만 수정 없이 최종 JSON으로 반환**한다. 서버가 승인 대기와 Run 종료를 저장했으므로 추가 조회·`work transition`·승인 polling 없이 종료한다. `NO_PURCHASE_REQUIRED`이면 그 응답의 DONE `executionResult`를 그대로 반환한다. CLI exit 0만으로 구매 완료를 판단하지 않는다. `error: PLAN_REQUIRES_RECALCULATION`처럼 `executionResult`가 없는 응답은 그대로 완료 결과로 쓰지 않는다. 실제로 확인한 오류와 입력 계획 참조를 FAILED로 보고한다. CLI가 HTTP 상태만 반환하면 409의 원인을 재계산 필요로 단정하지 않는다.

## 인간 결정 후 재개

새 Run에서 Case를 다시 읽는다. `purchasing`의 항목 중 현재 `workItemRef`와 일치하는 항목의 `planRef`, 승인 상태, `applicationId`, `purchaseOrderIds`를 확인한다. 다른 업무의 적용 결과를 완료 증거로 사용하지 않는다. 적용된 발주가 있으면 각 실제 ID를 `po show`로 조회하여 공급처·거점·자재·수량·단가·납기를 계획 및 적용 근거와 대조한 뒤 DONE을 제안한다. `resultRef`에는 실제 계획 참조를 쓴다. 서버는 실제 ERP 행과 승인된 번들의 일치를 다시 검증하고 DONE 트랜잭션 안에서 Orchestrator 담당 후속 책임을 생성한다. 모델 결과 제출 전에는 후속이 아직 없을 수 있으므로 후속 조회를 DONE 전제조건으로 삼지 않는다.

거절·BLOCKED·적용 불일치이면 상태와 필요한 인간 조치를 FAILED로 보고하며 승인 재요청이나 우회 발주를 만들지 않는다. 승인 대기 자체는 실패가 아니다. 이미 저장된 대기는 실행을 끝내고, 기존 승인 참조의 서버 실행 결과 계약을 유지한다.

## 남은 책임

인증 만료와 30일 이내 만료는 검토 대상으로 명시한다. 만료 시 입고 차단 판단은 QC 소관이며 현재 직접 실행할 CLI는 없다. 전자세금계산서 필드 `tax_invoice_number` / `tax_invoice_date`를 보존하고 값을 추측하지 않는다.

Procurement DONE은 구매 단계 완료다. 실제 계획·발주 참조와 남은 입고 확인·생산/재고 검토를 설명한다. 검증된 DONE과 함께 서버가 후속 책임을 저장하고 Case를 WAITING으로 유지한다. 후속 참조를 미리 지어내거나 별도 업무로 생성하지 않는다. `NO_PURCHASE_REQUIRED`는 종료된 응답을 그대로 반환하고 무효화된 capability로 후속 조회하지 않는다. 이 경우도 서버가 실제 계획의 생산 필요 여부에 따른 검토 책임을 남기며 가짜 발주·납기를 만들지 않는다. 물리적 생산·입고는 수행하지 않으며 재고 회복이나 Case 전체 완료를 주장하지 않는다.
