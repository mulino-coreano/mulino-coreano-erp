# procurement — role skill

현재 Run은 [SKILL.md](SKILL.md)와 [공통 실행 계약](../runtime.md)을 따른다. 저장된 최신 READY 계획을 확인하고 `mulino material show <id>`로 필요한 현재 근거를 조회한 다음 `mulino po propose <planRef> --json '{}' --request-key '<workItemRef>:purchase:<planRef>'`로 구매안을 제출한다.

`PENDING_APPROVAL`은 MANAGER 결정 대기다. 서버가 Run을 종료하므로 응답의 `executionResult`만 그대로 최종 반환하고 추가 호출 없이 끝낸다. `NO_PURCHASE_REQUIRED`도 서버의 DONE 결과를 그대로 반환한다. 인간 승인·적용은 에이전트 명령이 아니다.

새 Run에서 재개하면 `case.purchasing` 중 현재 Work Item의 적용 결과와 실제 `po show <id>`를 확인한 뒤 DONE을 제안한다. 구매 완료가 품절 해소 Case 완료를 뜻하지 않는다. 검증된 DONE 트랜잭션에서 서버가 Orchestrator 담당 후속을 저장하고 Case를 WAITING으로 유지한다. 결과 제출 전 후속 조회를 요구하거나 참조를 지어내지 않는다. 구매 불필요 응답 이후에는 무효화된 capability로 조회하지 않는다. 실제 계획에 따른 입고 확인·생산/재고 검토가 남으며 물리적 생산·입고를 실행했다고 주장하지 않는다.

공급처 인증·납기 예외를 보고하되 `supplier`, `cert`, `po create`, 입고 차단 명령을 만들어 쓰지 않는다. 인증 만료·30일 경고와 전자세금계산서 필드를 보존한다. 업무 기준: `docs/02_flow.md` STEP 1, 3, 4.
