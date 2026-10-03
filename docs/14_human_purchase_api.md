# 인간 답변·구매 결정과 후속 책임

일반 답변으로 ERP 쓰기를 승인하거나 발주만으로 Case를 끝내면
인간의 권한과 남은 입고·생산 책임을 잃는다. #45·#50·#51·#52는
local 프로필에서 이 경계를 분리한다. #33의 다른 ERP 승인 매트릭스,
#34의 데모 DB 검증, 실제 모델 실행과 다중 채널 UAT는 남아 있다.

## 인간 답변

`POST /api/v1/attention/{id}/answer`는 활성 OPERATOR·MANAGER의
HumanActor와 `work:write`를 요구한다. 본문은 `answer`,
`expectedVersion`, `scope` (`THIS_ACTION` 또는 `THIS_CASE`)이고
`Idempotency-Key`가 필수다. 조회 `GET /api/v1/attention`은 현재
`version`과 연결된 `governanceActionId`를 제공한다.

답변 원본은 `attention_requests`다. 업무 판단은 `decisions`에
`sourceAttentionId`·`sourceAttentionVersion`·`HUMAN_CONTEXT_ANSWER`
metadata와 함께 기록한다. 답변·판단·참여자·감사 Event·재개 Run은
한 transaction에 속한다. 재개 snapshot에도 판단 metadata가 남는다.
같은 인간의 같은 key와 입력은 원본 결과를 반환한다.

모든 Attention UPDATE는 version을 증가시킨다. stale version,
EXPIRED·CANCELLED·ANSWERED 요청, terminal Case/Work Item,
다른 Case의 Work Item, 무권한·비활성 인간은 답변할 수 없다.
정정은 새 Attention을 만들고 기존 기록을 참조한다. 기존 답변을
덮어쓰거나 THIS_CASE를 미래 구매 자동 승인 정책으로 확대하지 않는다.

AUTHORITY_REQUIRED 또는 구매 승인에 연결된 Attention은 일반 답변
API로 처리하지 않는다. 일반 답변의 `Approved` 문자열도
`CHANGE_REQUEST_APPROVED`의 승인 원본이 될 수 없다.

## 구매 제안과 결정

| API | 권한과 결과 |
|---|---|
| `POST /plans/{ref}/purchase-proposal` | live `agent:PROCUREMENT` capability, 빈 본문, 필수 key. 저장된 계획으로 제안하며 발주를 만들지 않는다. |
| `GET /approvals/{id}` | 인간 `erp:read`. 불변 계획의 수량·공급·출처·계산 근거, version/hash와 미조치 영향을 조회한다. |
| `POST /approvals/{id}/decision` | HumanActor의 `procurement:decide`와 DB의 활성 MANAGER가 모두 필요하다. ServiceActor·AgentActor는 금지다. |
| `GET /purchase-orders/{id}` | 인간 `erp:read`. 실제 발주·품목·입고 수량과 승인 연결을 조회한다. |

결정 본문은 `decision` (`APPROVE` 또는 `BLOCK`),
`expectedVersion`, `proposalHash`, `reason`이다. 필수 key는
사용자 지시 재전송에만 재사용한다. 새 답변·구매 작업 namespace는
객체 key 순서와 무관한 canonical hash를 쓴다. 기존 Case·계획 receipt의
hash 방식은 유지한다.

APPROVE는 현재 계획·현재 source를 잠금 아래 재계산한다. 가격·수량·
공급처·납기·공급·BOM·정책 또는 계획 version이 달라지면 제안을
EXPIRED로 남기고 409를 반환한다. 승인 성공은 공급처별 발주 묶음,
최종 Governance Decision, 불변 audit, application 검증 receipt와
구매 Work Item의 DONE·후속 WAITING과 승인·의존 해소 Event를 함께 기록한다. 일부 실패는 모두
rollback한다. 동일 승인 재전송은 이미 만든 같은 발주 묶음을 반환한다.
BLOCK은 발주를 만들지 않고 구매 Work Item을 취소하고 상위 방침
Attention을 남긴다. terminal 책임에 새 결정을 적용하지 않는다.

`purchase_order_items.unit_price`는 기존 NUMERIC(15,2)다.
base-unit 단가는 `round(purchase_unit_price / conversion, 2)`다.
실제 금액은 NUMERIC(18,6)의 구매 단가·구매 수량으로 계산해
`line_amount = round(purchase_quantity * purchase_unit_price, 0)`에
보존한다. 반올림한 base-unit 단가로 총액을 재계산하지 않는다.
KG/G와 L/ML 변환에도 이 계약을 적용한다.

## 발주 뒤의 책임

APPROVE transaction에서 서버가 실제 발주 묶음을 검증하고 application을
저장한 뒤 구매 Work Item을 DONE으로 끝낸다. 가장 이른 미입고 납기의
`replenishment_followups`와 WAITING Work Item도 함께 만든다.
추가 모델 확인 Run은 예약하지 않는다. 승인 Event는 원래 구매 scope를
유지하고 DONE 업무를 다시 열지 않는다. DONE 의존 Event는 대기하던
상위 Orchestrator를 이어 간다. 구매 불필요 계획은 기존 서버 검증을
거친 NO_PURCHASE_REQUIRED Run의 DONE에서 후속 책임을 만든다. 이 업무는 서버가 관찰하며 모델 Run 예약·일반 transition으로
완료할 수 없다. Dispatcher sweep은 ERP 입고를 다시 읽는다.

관찰은 입고·생산 실적을 생성하지 않는다. 입고가 확인돼도 남은
생산·재고 검토를 보존하며 Case를 RESOLVED·CLOSED로 만들지 않는다.
ACT intake의 초기 Run 예약·재보충 scope 생성과 실제 runtime 연결은
후속 #53·#24 범위다.

## 로컬 stdio

MCP는 `whoami`, `get_case`, `get_plan`, `get_approval`,
`decide_purchase`, `answer_attention`, `get_purchase_order`를 제공한다.
인간 도구는 `MULINO_LOCAL_ROLE` (기본 OPERATOR)을
`X-Mulino-Local-Role`로만 전달한다. service secret, run capability,
`Authorization`, `/internal/runs`, `/agent/**`를 쓰지 않는다.
결정 도구는 read-only가 아니며 version/hash와 인간의 선택이 필요하다.
requestKey를 생략하면 UUID를 생성하고 오류에도 반환한다.
Node 22 이상에서 unsafe integer ID와 decimal JSON 숫자를 문자열로
보존해 JavaScript 반올림을 막는다. 응답은 token·lease 비밀을 제공하지
않는다. OAuth·Auth0 transport는 포함하지 않는다.

`!local`에서는 새 답변·구매 API를 모두 거부한다. 기존 public
foundation surface와 local service·agent 인증 체인의 경계는 유지한다.

## 스키마와 검증

Flyway V24~V26과 독립 DDL 16~18은 각각 Attention version,
구매 승인/application, 후속 책임을 추가한다. V1~V23은 변경하지 않는다.
독립 DDL은 00~18 번호 순서 뒤 interface/allergens seed를 적용한다.
legacy 발주 base 가격과 다단계 거버넌스 기록의 업그레이드를 검증한다.

검증은 실제 PostgreSQL 통합 테스트, backend test/bootJar, local HTTP와
stdio MCP 업무 흐름, 빈 DB의 Flyway/독립 DDL schema 비교로 구분한다.
scripted fixture 통과는 실제 모델·Auth0·다중 채널 UAT의 증거가 아니다.

2026-10-03 검증에서는 Java 21·PostgreSQL 18.6에서
`./gradlew test bootJar --no-daemon` 453개가 통과했다.
Spring test context의 connection 고갈을 막기 위해 test resource에만
Hikari max 4·min idle 1을 설정했다. production 설정은 바꾸지 않았다.
`npm test` 14개와 별도 fixture DB의 HTTP·stdio 7도구 흐름도 통과했다.
승인 전 application은 0개, MANAGER 승인 뒤 16,500원 발주 묶음은
1개였고 재전송도 같은 묶음이었다. 승인 응답 직후 구매는 DONE이고 Case와 후속
업무는 WAITING이고 새 입고는 0개였다. VIEWER 쓰기 거부와 무역할
구매 조회 401도 확인했다. Flyway와 독립 DDL의 schema는 기존
`events.external_ref` 컬럼 순서만 정규화한 뒤 일치했다.
