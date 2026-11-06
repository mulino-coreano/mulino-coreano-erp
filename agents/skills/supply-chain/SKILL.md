---
name: supply-chain
description: Use when a Mulino SUPPLY_CHAIN Run needs a persisted replenishment calculation from order history, multilevel BOM, inventory and supplier terms, or when reviewing stock and LOT traceability concerns.
---

# Supply Chain

공급망 역할은 재보충 필요량과 근거를 계산한다. 현재 실행 가능한 범위는 서버의 재보충 계획 API다. 먼저 [공통 실행 계약](../runtime.md)을 읽는다. 업무 흐름 기준은 저장소의 `docs/02_flow.md`이며 생산·입고·출고를 직접 변경하지 않는다.

## 계산 절차

1. `mulino case show <caseRef>`로 현재 업무와 `caseMetadata.replenishment`를 읽는다. 요청의 `warehouseId`, `productIds`, `targetDate`를 유지한다. 범위가 없거나 모호하면 필요한 값을 구체적으로 설명하고 FAILED를 반환한다.
2. `mulino plan calculate <caseRef> --json '<본문>' --request-key '<workItemRef>:plan:initial'`을 호출한다. 본문은 `{"warehouseId":1,"productIds":[1,2]}`처럼 정확한 Case 범위를 사용한다. `horizonDays`를 생략하면 서버가 목표 날짜로부터 계산한다. 오늘 기준으로 30일을 다시 더하지 않는다. 단위·소수·날짜 계산은 서버가 수행한다.
3. 반환된 계획 `ref`로 `mulino plan show <ref>`를 호출해 실제 저장을 확인한다. `result.status`가 READY이고 현재 업무에서 만든 최신 계획일 때만 완료를 제안한다. NEEDS_ATTENTION 또는 계산 거부이면 원인·요구 자료·반환된 참조를 설명하고 FAILED로 끝낸다. 과거 READY 결과를 현재 실패의 완료 근거로 사용하지 않는다.
4. 최종 JSON의 `resultRef`에 계획 참조를 넣고 수요 기준일, 생산·자재 소요량, 선택 공급처·수량·금액, 검토 사항을 `summary`에 짧게 설명한다. 후속 구매안 준비는 Orchestrator가 Procurement에 배정한다. 구매안을 직접 만들거나 승인하지 않는다.

```json
{"outcome":"DONE","summary":"서버에 재보충 계획을 저장했습니다. 선택 공급처와 구매량은 계획에 있으며 구매안 검토가 필요합니다.","waitingConditions":[],"resultRef":"PLAN-실제응답참조"}
```

CLI 성공만으로 업무가 끝나지 않는다. 실행기가 최종 결과를 전달하면 서버가 최신 계산 시도와 원본 업무 연결을 다시 검증한다. 명시적 전이가 필요하면 `mulino work transition <workItemRef> --json '{"outcome":"DONE","summary":"계획 저장 확인","waitingConditions":[]}' --request-key '<workItemRef>:finish'`를 사용하고 실제 응답을 따른다.

## 재고와 추적성

현재 가용 재고와 예정 입고를 구분한다. 생산·LOT·출고 수량 대사가 실패하면 임의 보정이나 로컬 계산으로 계속하지 않는다. `suppliers → purchase_orders → purchase_order_items → inbound → raw_material_lots → production_ingredients → production_lots → outbound_lots → outbound → orders → customers`의 순·역방향 관계를 보존한다. 출고 LOT 합계는 출고 수량과 같아야 하고 원재료 사용량은 잔량과 맞아야 한다.

FEFO·LOT trace·품질 조치의 CLI는 아직 제공되지 않는다. 해당 요청을 재보충 계산으로 대체하거나 미구현 명령을 만들어 실행하지 않는다. 품질·인증 예외는 검토 대상으로 알리며 원래 업무의 담당과 미완료 상태를 보존한다.
