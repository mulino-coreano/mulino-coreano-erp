# orchestrator — main-session skill

[SKILL.md](SKILL.md)와 [공통 실행 계약](../runtime.md)에 따라 Case의 저장된 의무를 조율한다. 다른 ERP 역할은 별도 capability의 영속 Work Item/Run에 배정한다. Native subagent는 현재 권한 내의 한정된 분석·검토만 수행한다.

공급망 계산 → 최신 READY 계획 확인 → 정확한 계획 참조로 Procurement 업무 배정 → DEPENDENCY_DONE 대기 → 재개 후 실제 구매 결과와 남은 의무 확인 순서다. `work create`의 본문과 요청 키는 부모 Work Item 및 계획별로 고정하여 재전송 시 같은 자식 참조를 복구한다. BLOCKED/실패를 새 업무나 반복 대기로 우회하지 않는다.

Procurement의 `po propose`는 MANAGER 승인 대기를 저장한다. 역할은 서버의 `executionResult`를 그대로 반환하고 실행을 종료한다. Orchestrator는 직접 구매안을 만들거나 인간 승인을 대행하지 않는다.

구매 DONE 후 `followups`에서 정확한 Case·계획·구매 자식과 현재 부모 `parentWorkItemRef` 연결 및 진행 중 의무를 확인한다. 서버가 저장한 후속 참조·담당·실제 기한·관찰 기준 시각과 남은 검토를 설명하여 원본 조정 업무 DONE을 제안한다. Case는 WAITING이다. 새 후속을 `work create`로 만들지 않는다. 후속 업무는 서버가 관리하며 인간 답변도 완료나 새 Run을 만들지 않는다. 입고 확인은 생산·재고 회복 완료가 아니다. QC의 입고·리콜 변경은 아직 설치된 CLI가 아니므로 필요한 경우 구체적인 미지원 작업으로 보고한다.

역할별 책임과 한국 규정 기준은 `docs/02_flow.md`다. 전체 dispatch 표와 결과 계약은 SKILL.md를 따른다.
