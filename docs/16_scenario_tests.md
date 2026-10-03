# 업무 시나리오 테스트

모델 호출을 대신한 스크립트의 성공만으로 실제 하네스가 업무를
수행한다고 판단할 수 없다. 같은 한국어 업무 시나리오를 scripted
SIT와 실제 모델 UAT에서 실행하고 DB 업무 상태와 Run 증거를 남긴다.
설계의 원본 이력과 통합 방향은
[시나리오 설계](superpowers/specs/2026-09-25-scenario-tests-design.md)를
따른다. 이 문서는 #57 이식 branch의 실행 계약을 설명한다.

## 실행 계층

| 명령 | 실행 범위 | 모델 호출 |
|---|---|---|
| `./gradlew test bootJar` | 기존 규칙·보안 테스트와 scenario helper | 없음 |
| `./gradlew sitTest` | MRP→P2P 5개 및 실제 backend 재시작 2건 | 없음 |
| `./gradlew pendingScenarios` | #26 QM 5개·#27 리콜 3개 dry-run 목록 | 없음 |
| `./gradlew uatTest` | 같은 Feature의 `@uat` 3개 | 비용 발생 |

Cucumber 8.0.1을 쓴다. Unit task의 sentinel tag는 업무 시나리오를
실행하지 않으며 Cucumber가 보고하는 제외 시나리오는 skipped다.
SIT/UAT는 `@pending`을 제외한다. pending task는 미정의 단계만
허용하며 다른 discovery·설정 실패를 성공으로 처리하지 않는다.
`-PscenarioTags=@TC-P2P-001`로 범위를 줄여도 task 기본 tag와 교집합을
취하므로 pending 업무가 일반 SIT/UAT에 들어오지 않는다.

## 접속과 고정 시계

Java 21, PostgreSQL 18, Node 24.16.0, Zig 0.16.0이 필요하다.
먼저 `agents/cli`에서 `zig build`하고 `mcp-server` 의존성을 설치한다.
backend에서 각 계층에 다른 폐기용 DB를 지정한다.

```bash
DB_URL=jdbc:postgresql://127.0.0.1:55447/mulino_sit_scenario \
DB_USERNAME=postgres DB_PASSWORD=TEST_DB_PASSWORD ./gradlew sitTest
```

시나리오는 Flyway schema를 초기화하므로 loopback의 `*_scenario` DB만
허용한다. 검사기는 Spring 시작 전 DB URL을 확인한다.
SIT/UAT 서버는 local profile이며 planningClock을
`2026-09-05T00:00:00Z`, Asia/Seoul로 고정한다. 실제 벽시계에 따른
lease는 고정하지 않는다. fixture 적재는 테스트 코드에서만 수행한다.

인간 행동은 실제 역할별 stdio MCP로 전달한다. 보충 접수는
`create_case`의 `replenishment`와 `requestKey`를 사용한다.
승인 ID와 일반 질문은 `list_attention`에서 현재 Case의 기록을
찾고 `get_approval` 또는 `answer_attention`으로 처리한다.
일반 `get_case` 응답에 없는 승인 collection을 가정하지 않는다.

host runner에는 테스트 서버와 같은 서비스 secret을 넣고 내부 요청은
`X-Mulino-Local-Service`를 쓴다. 컨테이너 API 주소는 실제 Spring
동적 port의 `host.docker.internal`이다. agent CLI의 capability Bearer와
호스트 서비스 secret은 서로 전달 경계를 넘지 않는다.

## 업무 확인

- TC-P2P-001: 승인 전 발주 0건, 구매안 16,500원, MANAGER 승인 후
  발주 1건·16,500원 및 입고 확인 WAITING.
- TC-P2P-002: 반려 후 발주·후속 업무 0건.
- TC-P2P-003: OPERATOR 승인 시도 후 구매안 PENDING·발주 0건.
- TC-P2P-004: 공급 단가 변경으로 옛 구매안 EXPIRED·발주 0건.
  명시적 인간 재계산 답변 이후 새 구매안·발주 17,000원.
- TC-P2P-005: 실행기를 실제로 종료하고 새 worker가 저장된 업무를
  이어서 처리한다. 계획은 1개이고 Supply Chain DONE도 1건이다.

추가 JUnit SIT는 승인 대기 중 Spring application context를 실제로
종료·재생성한 뒤 같은 계획을 승인해 발주가 1건만 생김을 확인한다.
구매 승인·적용 성공은 서버가 구매 Work Item을 즉시 DONE으로 만들고
후속 책임을 저장한다. Procurement 모델의 승인 후 확인 Run을
예약하거나 기다리지 않는다.

## UAT 증거와 제한

`MULINO_AGENT_RUNTIME`, `MULINO_AGENT_MODEL`, `MULINO_RUNTIME_IMAGE`,
`MULINO_AUTH_VOLUME`을 명시한다. image와 volume은 metadata만 검사하고
로그인 내용은 읽지 않는다. 설정·image·volume이 없으면 해당 UAT는
명시적으로 SKIPPED되며 누락 항목을 증거 파일에 기록한다.
존재하는 volume의 실제 로그인·모델 접근은 모델 실행 전까지 모른다.

증거는 `backend/build/uat/<날짜>/<TC-ID>.json`이다.
실행 runtime·모델, Claude가 보고한 resolvedModel, Run별 업무 결과·
실패 코드·비용·토큰과 최종 발주·Case 상태를 저장한다.
inputTokens는 CLI의 native uncached input이며 cacheReadTokens와
cacheWriteTokens를 별도로 보존한다. 누락된 지표는 null이다.
사용량이 누락되면 usageComplete=false이고 전체 비용을 만들지 않으며
보고된 비용 합계만 partialCostUsd에 남긴다.
`beforeHumanDecision`에는 실제 승인 전 PENDING·발주 0건과 그 시점의
Run 사용량을 별도로 저장한다. 사용량 로그가 없는 Run은
`usageReported=false`로 구분한다. model_finished 이벤트만 있고
사용량이 없는 경우도 보고된 사용량으로 취급하지 않는다. 모델 비용은 가격표로 추정하지 않는다.

각 역할은 source Work Item/Run으로 전환한다. 이 증거를 native
subagent 실행 성공으로 해석하지 않는다. QC·리콜 pending script는
해당 기능 구현이나 한국 규제 전체의 인수 증거가 아니다.

이미 종료된 backend COMPLETED 결과를 heartbeat로 확인하면 runner는
revoked capability를 갱신하지 않고 최대 60초의 종료 유예만 둔다.
이 유예는 전체 실행 상한 안에서 final JSON과 CLI 사용량을 수집한다.
유예 만료·shutdown은 자식을 종료하고 확정된 backend 결과를 유지하며
사용량 실패를 기록한다. 실제 UAT는 이 실패를 성공 상태 뒤에 숨기지 않는다.
승인 후에는 원본 조정 업무가 실제 후속 책임을 확인해 DONE이 되고,
그 Run의 사용량까지 수집한 뒤 시나리오를 끝낸다.

## 2026-10-03 검증 기록

`test bootJar`는 482건 통과, 시나리오 13건은 Unit에서 제외되어
skipped로 보고했고 92초가 걸렸다. SIT는 업무 5개와 실제 backend
재시작 검증 2건이 통과했다. pending 목록은 QM 5개·리콜 3개다.

실제 TC-P2P-001은 CLAUDE/claude-sonnet-5에서 통과했다. 승인 전
PENDING·발주 0건, 승인 후 발주 1건·16,500원, 원본 조정 업무 DONE과
Case WAITING을 확인했다. 5개 Run 모두 사용량이 완전하며 CLI 보고
비용 합계는 약 USD 3.0618774이다. native uncached input 120,
output 23,313, cache read 4,943,597, cache creation 459,947이다.
증거는 `backend/build/uat/2026-10-03/TC-P2P-001.json`이다.

첫 실패는 scoped proxy의 직접 port 필드 접근으로 API port 0을
전달한 문제다. CLI 보고 비용은 USD 0.43898200000000004다.
두 번째 시도는 PENDING에 도달했지만 terminal heartbeat가 모델을
즉시 취소해 Procurement 사용량을 잃었다. 알려진 비용만
USD 1.2156242이며 Procurement 비용은 unknown이다. 이 둘은
`backend/build/uat/2026-10-03/attempts/`에 별도 보존했다.
Procurement의 누락된 비용이 있어 전체 시도 비용은 알 수 없다.

실제 tagged UAT 3개를 모두 통과했다. TC-P2P-002는 반려 후 발주·후속
0건이며 5개 Run의 보고 비용은 약 USD 2.890354다. TC-P2P-004는
옛 제안 EXPIRED·발주 0건, 인간 재계산 지시 후 새 승인안 17,000원,
승인 후 발주 1건·Case WAITING과 조정 업무 DONE을 확인했다.
9개 Run의 보고 비용은 약 USD 6.216729다. 001은 351초, 002·004의
합동 task는 851초였다. 각 시나리오의 모든 Run은 사용량이 완전하고
resolvedModel은 claude-sonnet-5이며 machine failure는 없다.

성공한 세 시나리오의 보고 비용 합계는 약 USD 12.1689604다.
앞선 실패·불완전 시도의 알려진 비용까지 더하면 약
USD 13.8235666이지만 이전 Procurement 비용이 unknown이므로
전체 시도 비용은 확정하지 않는다. 전체 ledger와 Run 증거는
`backend/build/uat/2026-10-03/`에 보존했다. 실제 인수는 source
역할 Run의 검증이며 native subagent 수행 증거로 바꾸어 말하지 않는다.
