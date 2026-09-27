# 시나리오 테스트 체계 설계 (SAP Activate 기준)

작성일: 2026-09-25
관련 이슈: #24, #26, #27, #55

---

## 1. 배경

2026-09-25 실모델(claude-sonnet-5) 인수에서 스크립트 모델로는 통과하던
흐름이 두 번 깨졌다.

- 첫 번째: `$schema` 선언 거부 — Claude Code의 `--json-schema` 검사기가
  우리 결과 스키마의 draft 2020-12 `$schema` 선언을 거부했다. 스크립트
  에이전트는 하네스를 거치지 않아 드러나지 않았다.
- 두 번째: stream-json 한 줄 64 KiB 초과 — `stream-json` 출력이 CLI 도구
  응답(계획 31 KB, 근거 스냅샷 84 KB)을 한 줄에 실어 실행기의 64 KiB 한 줄
  제한을 넘었다.

현재 테스트 규모는 다음과 같다.

- backend: 402개 메서드
- runner: 41개
- mcp-server: 18개
- 오류 메시지 문구 단언: 75곳

소유자 원칙:

- 테스트는 목적 → 목표 → 목표를 증명하는 동작에서 나온다.
- 문자열 단언, 라벨, 요소 존재, 구현 내부 확인을 금지한다.
- 적은 수의 의미 있는 테스트를 선호한다.

---

## 2. 목적

한국 식품 규제에 맞춘 ERP에서 사람과 AI 에이전트가 같은 업무(Case)를
이어 처리하되, ERP 쓰기는 반드시 사람 승인을 거친다.

---

## 3. 목표

| 번호 | 목표 | 증명할 동작 |
|------|------|-------------|
| 1 | LOT 추적 사슬 | 역추적: 완제품 LOT → 원재료 LOT → 공급사 도달. 정추적: 원재료 LOT → 출고 → 고객 도달. `SUM(outbound_lots.lot_quantity) = outbound.quantity` 불변. 생산 투입 시 `raw_material_lots.remaining_quantity` 감소. |
| 2 | 승인 없는 쓰기 차단 | 발주 INSERT → MANAGER 미승인 시 차단. 입고 UPDATE(차단/보류) → QC 미승인 시 차단. 리콜 INSERT → ADMIN 미승인 시 차단. 반려 시 대상 레코드 변화 없음. 읽기는 승인 없이 통과. |
| 3 | 재보충 계산 정확성 | 소요량 계획과 구매 제안 금액이 손계산 근거와 일치한다. |
| 4 | 업무 연속성 | Case 대기 → 사건 발생 → 재개 흐름 완주. 런타임 재시작 후 Run 복구. 에이전트 실패 시 사람 질의 후 재개. |
| 5 | 에이전트 역할 수행과 권한 밖 쓰기 불가 | 에이전트가 자기 역할 범위 내 Case를 완주한다. 역할 밖 쓰기를 시도하면 거부된다. 런타임·모델 무관. |
| 6 | 한국 규제 | 22 알레르겐 매핑 누락 원재료 → 입고 차단. 인증 만료 공급사 → 입고 차단, 30일 전 알림. 리콜 기록 2년 보존. |

보드 시나리오 매핑:

- 시나리오 1(MRP→P2P 재보충): 목표 2·3·4·5
- 시나리오 2(QM 입고 검사): 목표 1·2·6
- 시나리오 3(배치 리콜): 목표 1·2·6

---

## 4. 계층 (SAP Activate)

| 계층 | 도구 | 범위 | 실행 명령 | 주기 |
|------|------|------|-----------|------|
| Unit | JUnit | 개별 목표 로직; 테스트 이름에 목표 번호 포함 | `./gradlew test` | 매 커밋 |
| SIT | Cucumber Gherkin; 실제 백엔드·CLI·MCP; 스크립트 에이전트 model.mjs | 업무 프로세스 전체 흐름 | `./gradlew sitTest` | 매 PR |
| UAT | 같은 Gherkin 스크립트; 실제 하네스(Claude Code/Codex); 실제 모델; 사람 역할도 스크립트대로 | 실모델 인수; 비용·증거 기록 | `./gradlew uatTest` | 필요 시 |
| Regression | SIT 전체 | 병합 직전 회귀 확인 | `./gradlew sitTest` | 병합 전 |

**도구 선택: Cucumber 8.0.1**

빌드 기준: Spring 7.0.9, JUnit 6 (Spring Boot 4.1 호환 확인).

Cucumber를 택한 이유: SAP 컨설팅 포트폴리오에서 현업이 읽는 업무 프로세스
단위 테스트 스크립트를 이 스택에서 구현하는 수단이다.

한계: 협업 없으면 Gherkin 유지 비용이 실익보다 커진다.

참고: SAP 자체는 Cucumber를 쓰지 않는다. Solution Manager/Cloud ALM,
eCATT, CBTA, Tricentis Tosca, S/4HANA Cloud Test Automation Tool이
SAP 표준 테스트 도구다. 이 설계는 SAP 방법론(계층 구조, 인수 기준)을
적용한 포트폴리오 프로젝트이며 SAP 도구 스택이 아니다.

---

## 5. 스크립트 형식

**위치**: `backend/src/test/resources/scenarios/`

**파일 = 업무 프로세스**:

- `mrp-p2p.feature` — MRP→P2P 재보충
- `qm-inbound-inspection.feature` — QM 입고 검사 (#26)
- `batch-recall.feature` — 배치 리콜 (#27)

**헤더 구조**:

```gherkin
# language: ko
기능: <업무 프로세스명>
  # SAP 모듈: MM / QM / PP
  # 목표: 2, 3, 4, 5
```

**태그**: `@TC-P2P-001 @sit @uat @MM`

정상 케이스와 예외 케이스를 모두 포함한다. 단계는 역할로 시작한다.

**예시 시나리오**:

```gherkin
# language: ko
기능: MRP→P2P 재보충
  # SAP 모듈: MM
  # 목표: 2, 3, 4, 5

  @TC-P2P-001 @sit @uat @MM
  시나리오: MANAGER 승인 후에만 발주가 생성된다
    조건 DEMO-AMR 가용 재고가 안전재고보다 적다
    만일 OPERATOR가 DEMO-AMR 재보충 목표를 접수한다
    그리고 에이전트가 소요량 계획과 구매 제안을 작성한다
    그러면 구매 제안은 승인 대기 상태다
    그리고 발주는 생성되지 않았다
    만일 MANAGER가 구매 제안을 승인한다
    그러면 발주 1건이 생성된다
```

---

## 6. 작성 규칙

**꼭 지켜야 할 것**:

- 모든 시나리오는 목표 번호와 연결한다.
- `그러면` 단계는 업무 상태로 표현한다:
  발주 건수, 승인 상태, LOT 잔량, Case 상태, 입고 차단.
- 단계는 업무 언어와 역할로 시작한다.
- 승인 게이트마다 승인·반려·권한 없음 케이스를 모두 작성한다.
- 숫자는 손계산 근거를 명시한다.
- SIT·UAT 모두 업무 시계를 픽스처 기준일(2026-09-05 Asia/Seoul)로 고정한다.
- 프로세스당 3~6 케이스를 기준으로 한다.
- 시나리오 개요(Scenario Outline)는 업무 규칙이 달라질 때만 쓴다.

**금지**:

- 오류 메시지 문구 단언
- 라벨 또는 UI 요소 존재 확인
- 이전 문구 부재 확인
- 구현 내부 메서드 호출
- JSON 필드 존재 확인

**나쁨/좋음 예시**:

| 나쁨 | 좋음 |
|------|------|
| `응답 메시지는 "Approval not found"` | `발주는 생성되지 않았다` |
| `totalKrw 필드가 있다` | `구매 제안 합계는 16,500원이다` |
| `오류 코드 CMN009` | `같은 제품의 진행 중 Case는 1건이다` |

---

## 7. SIT/UAT 에이전트 교체

| 항목 | SIT | UAT |
|------|-----|-----|
| 사람 역할 | 역할별 실제 stdio MCP | 역할별 실제 stdio MCP |
| 에이전트 | 실제 runner + mulino CLI + 스크립트 에이전트(model.mjs) | 실제 runner + 하네스(Claude Code/Codex) + 모델 |
| 런타임 환경 변수 | 해당 없음(스크립트 에이전트) | `MULINO_AGENT_RUNTIME`, `MULINO_AGENT_MODEL` |
| 백엔드 | 테스트 내 실제 Spring 구동; 고정 업무 시계 | 테스트 내 실제 Spring 구동; 고정 업무 시계 |
| 확인 방법 | DB 업무 상태 | DB 업무 상태 |
| 에이전트 단계 대기 | 기대 업무 상태 도달까지 최대 1분 | 기대 업무 상태 도달까지 최대 15분 |
| 에이전트 단계 실패 | `model_finished` 실패 코드 첨부 | `model_finished` 실패 코드 첨부 |

**계층별로 잡는 버그**:

- Unit: 로직 오류, 경계값 오류
- SIT: 에이전트-백엔드 연동 오류, 승인 게이트 누락
- UAT: 실모델 행동 편차($schema 거부, 스트림 크기 초과 등)
- Regression: 병합으로 인한 기존 흐름 파손

**UAT 조건**:

사전 조건(로그인 볼륨, Docker 이미지, 모델 접근) 없으면 건너뜀으로 보고한다.

증거 경로: `build/uat/<날짜>/<TC-ID>.json`

증거 포함 항목: 런타임, 모델, Run별 결과, 실패 코드, 비용, 토큰,
최종 업무 상태.

모델 편차 때문에 과정이 아니라 결과(업무 상태)만 확인한다.

**재사용 컴포넌트**: 픽스처, 고정 시계, model.mjs, runner, MCP 기동 코드.

**신규 구현 필요**: Cucumber 연결 코드, 실행기 종류 선택(스크립트 에이전트
또는 Docker 하네스).

`DemoE2eTest`는 MRP→P2P SIT 이관 완료 후 삭제한다.

---

## 8. 시나리오 2·3 보류 스크립트

### qm-inbound-inspection.feature (`@pending @issue-26`)

#26 기능 완료 조건: `@pending` 제거 후 SIT 통과.

케이스 5개:

1. 온도 이상 입고 → 원재료 LOT HOLD, QC 승인 대기
2. QC 승인 → 차단 확정
3. QC 아닌 역할 승인 시도 → 변화 없음
4. 22 알레르겐 매핑 누락 원재료 → 입고 차단
5. 인증 만료 공급사 입고 → 차단

### batch-recall.feature (`@pending @issue-27`)

#27 기능 완료 조건: `@pending` 제거 후 SIT 통과.

케이스:

1. 완제품 컴플레인 → 6단계 역추적 완주 + 영향 고객 정추적
2. ADMIN 승인 전 → 리콜 미생성
3. ADMIN 승인 후 → 대상 LOT RECALLED 상태, 식약처 보고 기록 생성

보고서에 `@pending` 시나리오 목록을 미구현 항목으로 분리 출력한다.

---

## 9. 기존 테스트 정리

소유자 확인 전 삭제하지 않는다. 분류표를 먼저 제시하고 확인 후 적용한다.
전후 테스트 수와 `./gradlew test` 실행 시간을 기록한다.

**분류 기준**:

| 분류 | 기준 |
|------|------|
| 유지 | 목표 로직을 직접 검증하며 SIT와 중복 없음 |
| 고쳐 쓰기 | 목표는 맞지만 문구 단언·구현 내부 확인 포함 |
| SIT 이관 | 흐름 전체를 검증하며 Unit 계층 부적합 |
| 삭제 | 문구 단언 전용이거나 이미 SIT로 대체됨 |

### 9.1 기준선 (2026-09-27, `mulino_test`/`mulino_scenario` 컨테이너 재생성 직후)

DB: OrbStack PostgreSQL 18 컨테이너 `mulino-scenario-pg`(localhost:55433). `mulino_test`를
`DROP DATABASE`/`CREATE DATABASE`로 재생성한 뒤 측정했다.

| 스위트 | 명령 | 건수 | Wall time |
|---|---|---|---|
| backend `test` | `DB_URL=jdbc:postgresql://localhost:55433/mulino_test DB_USERNAME=postgres DB_PASSWORD=test ./gradlew clean test --no-daemon` | 544 (실행 531, 스킵 13) | 1분 34초 |
| agents/runner | `cd agents/runner && npm test` | 52 | 0.7초 |
| mcp-server | `cd mcp-server && npm test` | 18 | 2.2초 |
| scripts/demo | `node --test scripts/demo/readiness.test.mjs` | 17 | 0.19초 |

backend 544건 중 13건은 `@pending` Cucumber 시나리오(`batch-recall.feature` 3, `mrp-p2p.feature` 5,
`qm-inbound-inspection.feature` 5)로, `test` 태스크의 센티널 태그 필터에 의해 스킵 처리되며 실행되지
않는다(`build/test-results/test/TEST-feature_classpath_scenarios-*.xml`의 `skipped="tests"` 확인).
실행된 531건 중 6건(`ScenarioGuardTest` 1, `UatEvidenceTest` 2, `AgentDriverTest` 3)은 이번 작업이
새로 만든 `scenario` 패키지 소속이라 본 분류표의 범위 밖이다. 아래 표는 나머지
**525건, `backend/src/test/java` 47개 클래스**(`interfacepackage`·`planning`·`procurement`·
`execution`·`security`가 대부분이고 `common`·`idempotency`·`followup`에 1개씩 있다)를 다룬다. `WithTestActor`,
`TestActorSecurityContextFactory`, `ReplenishmentDemoFixture`는 테스트 애너테이션이나 픽스처 지원
클래스일 뿐 `@Test` 메서드가 없어 표에서 제외했다.

### 9.2 backend 분류표 (525건, 47개 클래스)

| 파일 | 테스트 수 | 증명하는 목표 | 분류 | 근거 | 대상 메서드 |
|---|---|---|---|---|---|
| `BackendApplicationTests` | 1 | 없음 | 삭제 | Spring 컨텍스트 로드만 확인하는 스캐폴딩 테스트로 6대 목표 중 어느 것도 증명하지 않는다. | `contextLoads` |
| `common/exception/GlobalExceptionHandlerTest` | 3 | 없음 | 삭제 | 3건 전부 `jsonPath("$.code")`/`jsonPath("$.message")`로 응답 문구·코드만 단언하며, 실제 업무 쓰기(발주/입고/리콜)를 통하지 않는 합성 컨트롤러로 공통 예외 매핑 인프라 자체를 테스트한다. 목표에 대응하는 업무 동작이 없고 문구 단언 전용이다. | `responseStatusExceptionKeepsItsHttpStatus`, `annotatedConflictExceptionsStayConflicts`, `undeclaredFailuresStayInternal` |
| `execution/AgentWorkIntegrationTest` | 7 | 4, 5 | 유지 | 역할 범위 내 하위 업무 생성, 대기조건 충족 후 재개, Case 범위 밖 접근 차단을 DB 업무 상태로 검증한다. 문구 단언 없음. | — |
| `execution/PurchaseExecutionIntegrationTest` | 15 | 2, 4 | 유지 | 구매 승인 대기·거부·역할 확인을 Run/워크아이템 상태로 검증한다. `hasMessage("rollback probe")`(줄 144)는 트랜잭션 롤백을 강제하기 위해 테스트가 직접 던진 프로브 예외의 식별용이며 업무 오류 문구가 아니다. | — |
| `execution/RunClaimConcurrencyIntegrationTest` | 7 | 4, 5 | 유지 | 동시 클레임 시 단 하나의 실행만 허용, 리스 만료·하트비트 상한을 DB 상태와 횟수로 검증한다. | — |
| `execution/RunExecutionIntegrationTest` | 12 | 4, 5 | 고쳐 쓰기 | 대부분 업무 상태(런타임별 분리, 강제 종료 등)로 검증하지만 `alreadyLeasedWorkerReceivesSafeConflictCode` 1건은 `jsonPath("$.error").value("WORKER_ALREADY_LEASED")`만 확인하고 뒤따르는 업무 상태 확인이 없다. | `alreadyLeasedWorkerReceivesSafeConflictCode` |
| `execution/RunMigrationIntegrationTest` | 1 | 1, 4 | 유지 | 레거시 RUNNING Run이 마이그레이션 후 ABORTED/READY로, `context_snapshot`·이력 이벤트가 그대로 보존되는지 DB로 검증한다. | — |
| `execution/RunResultValidationIntegrationTest` | 15 | 2, 4 | 고쳐 쓰기 | 잘못된 완료 보고를 막는 로직을 업무 상태(Run 상태 불변 등)로도 검증하지만, 10건이 `jsonPath("$.error").value(...)`로 오류 코드를 함께 단언한다. 코드 단언은 제거하고 이미 존재하는 상태 단언만 남기면 된다. | `latestAttentionPlanPreventsHistoricalReadyPlanFromCompletingWork`, `unsequencedLegacyPlanCannotCompleteEvenWhenMetadataClaimsReady`, `dependencyAliasesCannotDisagree`, `scheduledAliasesCannotDisagree`, `waitingListIsBoundedToSixteenAndNeverLeavesPartialRows`, `nullWaitAndUnknownOutcomeAreInvalidResultsWithoutMutation`, `agentTransitionAlsoPublishesSafeResultCodes`, `staleLeaseHasADistinctSafeCode` |
| `followup/ReplenishmentFollowupServiceTest` | 3 | 1, 4 | 유지 | LOT 분할 합산, 서울 자정 경계, 부분입고/보류 재고 계산을 순수 계산 검증한다. | — |
| `idempotency/RequestIdempotencyIntegrationTest` | 5 | 4 | 고쳐 쓰기 | 재요청이 중복 효과를 내지 않음을 대부분 DB 건수로 검증하지만 `refusesToRunWithoutAnAtomicCallerTransaction` 1건은 `hasMessageContaining("transaction")`만으로 결론짓는다. | `refusesToRunWithoutAnAtomicCallerTransaction` |
| `interfacepackage/AttentionAnswerIntegrationTest` | 14 | 2, 4 | 고쳐 쓰기 | 사람 답변 큐잉·재검증을 업무 상태로 검증하는 큰 스위트지만 `generalContextAnswerCannotBecomeAnApprovalThroughEventIngestion` 1건이 `hasMessageContaining("human approval")`로 끝난다. | `generalContextAnswerCannotBecomeAnApprovalThroughEventIngestion` |
| `interfacepackage/CaseLifecycleIntakeIntegrationTest` | 8 | 4 | 유지 | Case 접수의 멱등성·스코프 롤백·재사용 규칙을 DB 상태로 검증한다. | — |
| `interfacepackage/CaseOverviewIntegrationTest` | 4 | 4 | 유지 | Case 조회가 담당자·대기·타임라인을 정확히 반영하는지 검증한다. | — |
| `interfacepackage/ContextSnapshotCompletenessIntegrationTest` | 7 | 4, 5 | 유지 | Run 컨텍스트 스냅샷이 조직·인식론적 맥락·의무를 정확한 수치로 보존하는지 검증한다. | — |
| `interfacepackage/DispatcherConcurrencyIntegrationTest` | 1 | 4, 5 | 유지 | 에이전트 비활성화와 디스패치 스케줄링의 동시성 경합을 검증한다. | — |
| `interfacepackage/DispatcherControllerIntegrationTest` | 17 | 2, 4 | 유지 | 이벤트/Run HTTP 계약(케이스 필터링, 중복 거부, 스코프 위반 거부)을 업무 상태로 검증한다. | — |
| `interfacepackage/DispatcherIntegrationTest` | 40 | 2, 4 | 고쳐 쓰기 | 이 파일은 이벤트 디스패치·승인 이벤트·대기조건 재개의 핵심 스위트로 대부분 DB 상태로 검증하지만, 12개 메서드가 `hasMessageContaining(...)`으로 거부 사유 문구를 함께 확인한다. | `reusedIdempotencyKeyWithDifferentPayloadIsRejected`, `replayOfLegacyNullPayloadEventIsRejectedAsContentConflict`, `publicEventIngestionRequiresNonBlankIdempotencyKey`, `eventPayloadIdentityCannotContradictResolvedScope`, `approvalEventRejectsDecisionTextWithoutAnAuthoritativeApprovalIdentity`, `governanceApprovalRejectsAMissingDeclaredAuthoritativeResource`, `governanceApprovalRejectsCallerScopeForAnUnmappedBusinessResource`, `globallyScopedGovernanceApprovalRejectsClaimEvidenceCaseNarrowing`, `eventRejectsClaimAndEvidenceFromDifferentCasesBeforeInsertion`, `externalSweepCannotForgeAnUnfinishedDependency`, `dependencyStatusEventRejectsAClaimAboutANonexistentWorkItem`, `dependencyStatusEventRejectsContradictorySourceAliasesBeforeInsertion` |
| `interfacepackage/DispatcherSchemaIntegrationTest` | 8 | 4 | 고쳐 쓰기 | append-only 트리거 3건이 `hasMessageContaining("events is append-only")`만 확인하고 이벤트 로우가 실제로 바뀌지 않았는지는 재조회하지 않는다. | `eventsRejectUpdatesBecauseTheyAreAppendOnly`, `eventsRejectDeletesBecauseTheyAreAppendOnly`, `eventsRejectTruncatesBecauseTheyAreAppendOnly` |
| `interfacepackage/InterfaceIntakeIntegrationTest` | 7 | 4 | 유지 | Case 생성/조회 HTTP 계약을 원장 상태로 검증한다. | — |
| `interfacepackage/InterfaceMonitorIntegrationTest` | 5 | 4 | 유지 | Case 위험 판정(초과 업무, 자재 예외)이 정확히 한 번만 집계되는지 검증한다. | — |
| `interfacepackage/InterfaceQueryIntegrationTest` | 5 | 4, 5 | 유지 | 재고 조회·검색이 SQL 인젝션을 리터럴로 처리하고 정확한 담당자를 식별하는지 검증한다. | — |
| `interfacepackage/RunSchedulingIntegrationTest` | 23 | 2, 4 | 고쳐 쓰기 | 대부분 이미 Run 건수 0/충돌을 함께 확인하지만, 공용 헬퍼 `assertRejectedBeforeInsert`를 포함해 9개 메서드가 `hasMessageContaining(...)`을 덧붙인다. | `terminalCaseRejectsBothWorkAndCaseRunScheduling`, `publicRunCreationReportsAnActiveRunConflictAtomically`, `createRunRejectsWorkItemFromAnotherCaseBeforeInsert`, `caseOnlyRunRejectsInactiveRequestedAgentBeforeInsert`, `directCreateRunRejectsMalformedValuesBeforeDatabaseLookup`, `createRunRejectsWorkItemThatIsNotReadyBeforeInsert`, `createRunRejectsWorkItemAssignedToAUserBeforeInsert`, `createRunRejectsWorkItemFromAnotherCaseBeforeInsert`, `createRunRejectsRequestedAgentThatDoesNotMatchLockedAssignment` |
| `interfacepackage/RunServiceIntegrationTest` | 12 | 4 | 유지 | 컨텍스트 재구성·폴백 스냅샷 로직을 정확한 스냅샷 내용으로 검증한다. | — |
| `interfacepackage/RunServiceRuntimeTest` | 2 | 5 | 유지 | 런타임 기본값 결정과 미지원 런타임 거부를 검증한다. | — |
| `interfacepackage/RunServiceTransactionIntegrationTest` | 2 | 4 | 유지 | 세이브포인트 복구와 Run 생성 커밋 전 워크아이템 잠금을 검증한다. | — |
| `interfacepackage/SchemaReviewIntegrationTest` | 4 | 4 | 유지 | Case 범위를 벗어난 참가자·대상 참조를 DB 제약으로 거부하는지 검증한다. | — |
| `interfacepackage/WaitingConditionMatcherTest` | 59 | 4 | 유지 | 대기조건 매칭기가 문서화된 조건만 안전하게 매칭하고 잘못된 셀렉터는 닫힌 실패로 처리하는지 순수 로직으로 검증한다. | — |
| `planning/BomPlannerTest` | 11 | 3 | 고쳐 쓰기 | BOM 소요량 계산의 핵심 불변(순삭감, FEFO, 배치 라운딩)을 대부분 정확한 수량으로 검증하지만, 3개 메서드가 `hasMessageContaining(BOM_CYCLE 등 8개 코드)`로 거부 사유를 판별한다. | `rejectsCyclesAmbiguousVersionsMissingBomAndImpossibleProductionDates`, `rejectsDuplicateSourcesUnitMismatchAndInvalidQuantities`, `refusesVersionThatExpiresBeforeUseOrStartsAfterProductionStart` |
| `planning/CanonicalJsonTest` | 4 | 1, 3 | 유지 | 목표 1·3이 요구하는 정확한 소수점·해시 안정성을 뒷받침하는 공통 유틸리티를 검증한다. | — |
| `planning/ForecastServiceTest` | 25 | 3 | 유지 | 수요예측 이력 집계·안전재고 계산의 경계값을 정확한 수치로 검증한다. | — |
| `planning/PlanningSchemaIntegrationTest` | 7 | 1, 3 | 유지 | BOM 버전 중복·순환·단위 변환을 DB 제약으로 검증한다. | — |
| `planning/PlanningSnapshotRepositoryIntegrationTest` | 22 | 1 | 고쳐 쓰기 | 재고/LOT 스냅샷 로딩이 실제 공급량·제외 사유·소요량으로 정확히 구성되는지 검증하는 목표 1의 핵심 스위트지만, 14개 메서드가 `hasMessageContaining(STOCK_LOT_MISMATCH 등 대문자 코드)`로 어떤 불변조건이 깨졌는지 판별한다. 코드 문자열 대신 위반된 업무 상태(예: 잔량 불일치 자체)를 직접 확인하도록 고쳐야 한다. | `rejectsCallerTransactionWithoutSnapshotIsolation`, `rejectsStockThatDisagreesWithLotResiduals`, `rejectsPositiveProductLotWithoutKnownWarehouse`, `rejectsProductConsumptionAboveOriginalQuantity`, `rejectsRawResidualMismatch`, `rejectsOutboundWhoseLotAllocationsDoNotMatchShipment`, `delayedPurchaseIsExcludedAndReceiptQuantityMustReconcile`, `requiresPolicyActiveFinishedGoodsAndRecognizedUnit`, `inactiveRequestedProductAndMissingPolicyFailExplicitly`, `rejectsCurrentRawLotWhoseInboundPointsAtUnrelatedPurchaseItem`, `rejectsRawLotSupplyInventedBeyondRecordedReceipt`, `rejectsReceiptWithPartiallyAllocatedLotQuantity`, `rejectsReceiptWithoutAnyAllocatedLot`, `rejectsSiblingLotAllocatedToUnrelatedMaterialEvenWhenReceiptTotalMatches` |
| `planning/PlanPersistenceIntegrationTest` | 22 | 3, 4 | 고쳐 쓰기 | 재보충 계산의 멱등 재생·리스 경합·버전 관리를 대부분 정확한 값으로 검증하는 큰 스위트지만, 2개 메서드가 DB 제약 위반 메시지(`"append-only"`, `"fk_work_item_latest_planning_plan"`)를 확인 수단으로 쓴다. | `replayReturnsOriginalAndNewKeyAppendsFreshSourceVersion`, `markerCannotPointToAPlanFromAnotherCaseOrWorkItem` |
| `planning/ReplenishmentCalculatorIntegrationTest` | 1 | 3 | 유지 | 실제 ERP 픽스처가 손계산 결과와 일치하는 계획을 만드는지 검증하며 트랜잭션을 변경하지 않는다. | — |
| `planning/ReplenishmentCalculatorTest` | 3 | 3 | 유지 | 이력·안전재고·생산·구매를 잇는 계산 로직을 순수 단위로 검증한다. | — |
| `planning/ReplenishmentDemoFixtureIntegrationTest` | 1 | 1, 3 | 유지 | 데모 픽스처가 물리/사용 가능 공급을 모두 재현하는지 검증한다. | — |
| `planning/SupplierSelectionServiceTest` | 54 | 3, 6 | 유지 | 공급사 선정 로직(총원가 최저, MOQ, 라운딩)과 22종 인증서 만료·갱신 판정(목표 6)을 정확한 값으로 검증한다. | — |
| `procurement/PurchaseBundleAssemblerIntegrationTest` | 1 | 3 | 유지 | 실제 계획 픽스처가 16,500원 한 건의 공급사 주문으로 귀결되는지 ERP 쓰기 없이 검증한다(스킬 §4의 좋은 예시와 동일한 패턴). | — |
| `procurement/PurchaseBundleAssemblerTest` | 7 | 3 | 유지 | 구매 묶음 계산(단위 변환, 반올림, 공급사별 그룹화)을 정확한 금액으로 검증한다. | — |
| `procurement/PurchaseReadIntegrationTest` | 2 | 2, 3 | 유지 | 구매 조회가 승인·매입단위를 지어내지 않고 정확한 소수점·누락 필드를 그대로 보여주는지 검증한다. | — |
| `procurement/PurchaseSchemaIntegrationTest` | 13 | 2, 3 | 유지 | 발주/제안/결재의 유일성·불변성·감사이력 append-only를 DB 제약으로 검증한다. | — |
| `procurement/PurchaseWorkflowIntegrationTest` | 32 | 2, 3 | 고쳐 쓰기 | MANAGER 승인 전후 발주 미생성/생성을 업무 상태로 검증하는 핵심 스위트지만, 6개 메서드가 `jsonPath`/`hasMessageContaining`으로 `STALE_LEASE`/`COMPLETION_NOT_VERIFIED`/`SERVER_MANAGED_WORK_ITEM` 코드를 확인한다(`"followup failure"`는 테스트가 트리거로 주입한 프로브 문구라 제외했다). | `planReadRechecksTheLeaseBeforeReturningItsEvidence`, `scopedReadsRecheckCapabilityAfterCurrentFactsAreLoaded`, `scopedOrderRechecksCapabilityAfterProjection`, `purchaseRevertedToDraftCannotBeReportedAsVerifiedCompletion`, `serverManagedWorkCannotBypassDispatchQueueClaimOrAttentionAnswer`, `followupMetadataCannotGrantAnUnrelatedParentCompletion` |
| `procurement/ReplenishmentFollowupIntegrationTest` | 8 | 1, 4 | 고쳐 쓰기 | 후속 확인 업무의 원자성·중복 방지를 대부분 DB 건수로 검증하지만 `forgedOrCrossCaseParentCannotCreateResponsibility` 1건이 `hasMessageContaining("FOLLOWUP_PARENT_INVALID")`로 끝난다(`transactionRollbackRemovesNewResponsibility`의 `hasMessage("rollback")`은 테스트 자체 프로브라 제외). | `forgedOrCrossCaseParentCannotCreateResponsibility` |
| `security/AuthenticationBoundaryIntegrationTest` | 1 | 2, 5 | 유지 | 익명 요청이 헬스체크조차 읽을 수 없음을 검증한다. | — |
| `security/HttpErrorSecurityIntegrationTest` | 6 | 5 | 유지 | 인증된 호출자의 오류 라우팅이 상태 코드 경계(404/400/403)를 유지하는지 검증한다. | — |
| `security/LocalActorCapabilitiesTest` | 5 | 2, 5 | 유지 | 역할별 쓰기/결정 권한(MANAGER만 구매 결정 등)을 검증한다. | — |
| `security/LocalSecurityIntegrationTest` | 13 | 2, 5 | 유지 | 사람/서비스 액터 구분과 역할별 Case 생성·디스패처 쓰기 차단을 검증한다. | — |

### 9.3 agents/runner, mcp-server, scripts/demo 분류표

| 파일 | 테스트 수 | 증명하는 목표 | 분류 | 근거 | 대상 메서드 |
|---|---|---|---|---|---|
| `agents/runner/test/executor.test.js` | 9 | 4, 5 | 유지 | 자식 프로세스가 역할이 지정한 컨텍스트만 받고, 알려진 토큰이 출력에서 redaction되며, Docker 격리(마운트/환경/권한)가 고정되는지 검증한다. | — |
| `agents/runner/test/protocol-auth.test.js` | 14 | 2, 4, 5 | 유지 | 워커 인증·리스 충돌·승인 대기 위조 방지를 검증한다. `error.message.includes('...secret...')`는 비밀이 새지 '않았음'을 확인하는 보안 단언이지 업무 오류 문구 검증이 아니다. | — |
| `agents/runner/test/runner.test.js` | 24 | 2, 4, 5 | 유지 | 클레임·하트비트·완료 보고 루프 전체와 승인 대기 위조 차단, 정확한 소수점 보존을 검증한다. | — |
| `agents/runner/test/runtime.test.js` | 5 | 5 | 유지 | 역할별 스킬 경로 고정, 샌드박스 플래그, 경로 탈출(`../../escape`) 차단을 검증한다. | — |
| `mcp-server/test/api-client.test.js` | 1 | 1, 3 | 유지 | 백엔드 응답의 큰 정수·소수 자릿수가 API 클라이언트를 거치며 그대로 보존되는지 검증한다. | — |
| `mcp-server/test/approval-evidence.test.js` | 2 | 2 | 유지 | 사람에게 보여줄 승인 근거 텍스트가 정확한 수량·근거를 포함하고 근거 누락 시 명시적으로 "미제공"이라고 밝히는지 검증한다. 텍스트 포함 확인은 오류 문구가 아니라 승인자에게 실제로 노출되는 업무 근거 자체다. | — |
| `mcp-server/test/server.test.js` | 15 | 2, 4, 5 | 유지 | MCP 도구가 역할 범위 밖 쓰기·위조된 권한을 거부하고, 사람 쓰기가 재시도 없이 정확한 키를 유지하며, 대기·승인 근거를 조작 없이 드러내는지 검증한다. | — |
| `scripts/demo/readiness.test.mjs` | 17 | 없음 | 유지 | ERP 6대 목표가 아니라 데모 준비도구 자체의 안전성(DSN 안전성, 비밀 redaction, SSRF 방지, 타임아웃)을 검증한다. 목표에 대응하지 않지만 중복이나 문구 단언 전용이 아니라 실제 배포 안전 회귀를 잡으므로 삭제를 권하지 않는다. 소유자가 이 파일이 정리 대상 범위에 포함되는지 판단이 필요하다. | — |

### 9.4 요약

- 대상: backend 47개 클래스(525건) + runner 4개 파일(52건) + mcp-server 3개 파일(18건) + demo 1개 파일(17건) = 55개 파일/클래스, 612건.
- 유지: 41개 파일(backend 33, runner 4, mcp-server 3, demo 1).
- 고쳐 쓰기: 12개 backend 클래스(`RunExecutionIntegrationTest`, `RunResultValidationIntegrationTest`, `RequestIdempotencyIntegrationTest`, `AttentionAnswerIntegrationTest`, `DispatcherIntegrationTest`, `DispatcherSchemaIntegrationTest`, `RunSchedulingIntegrationTest`, `BomPlannerTest`, `PlanningSnapshotRepositoryIntegrationTest`, `PlanPersistenceIntegrationTest`, `PurchaseWorkflowIntegrationTest`, `ReplenishmentFollowupIntegrationTest`), 영향 메서드 합계 61개(해당 클래스들의 나머지 메서드는 이미 업무 상태 단언을 병행하고 있어, 코드/메시지 단언 줄만 제거하면 되는 경우가 대부분이다).
- SIT 이관: 0건. 기존 클래스 중 흐름 전체를 Unit이 잘못 떠맡고 있는 사례는 발견하지 못했다(`DemoE2eTest`는 이미 이전 태스크에서 `BackendRestartRecoveryTest`로 이관·폐기됨).
- 삭제: 2개 클래스, 4건(`BackendApplicationTests` 1건, `GlobalExceptionHandlerTest` 3건). 목표를 증명하지 않는 순수 인프라/스캐폴딩 테스트로 한정했다.
- 예상 순감: 삭제 승인 시 525건 → 521건(backend, -4), 실행 시간 변화는 두 클래스 모두 수 ms대라 유의미한 단축은 없다. "고쳐 쓰기"는 삭제가 아니므로 건수는 그대로다.

## 10. 범위 밖

- 시나리오 2·3 기능 구현 (#26, #27)
- Codex 실모델 실행
- 대시보드 테스트
