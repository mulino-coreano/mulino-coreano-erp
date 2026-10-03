# Dispatcher 범위와 실행 시점 수정 (#54)

다른 발주의 답신으로 업무를 재개하거나 글로벌 사실을 한 Case에만
전달하면 남은 책임이 사라진다. #54는 supplier/PO 식별자, Event의
기록 범위와 대기 검색 범위, 모니터 카운트와 Run 시점을 수정한다.
source의 repository 분리 전체를 이식하지 않고 현재 inline JDBC
`DispatcherService`·`InterfaceService`에 필요한 변경만 적용한다.

## 업무 계약

- SUPPLIER_REPLY 조건이 supplier와 PO를 모두 지정하면 둘 다
  일치해야 한다. 하나만 지정하면 그 식별자만 검사한다. 식별자가
  없거나 alias가 서로 모순되면 대기를 해소하지 않는다.
- `casesOpen`은 OPEN·IN_PROGRESS·WAITING을 센다. `casesAtRisk`는
  같은 상태 집합 안의 위험 Case만 세므로 열림 수보다 클 수 없다.
- Case 없이 들어온 Event는 claim/evidence를 연결해도 GLOBAL로
  남는다. Claim과 Evidence가 같은 Case인지 검증하는 규칙은 유지한다.
- Attention 승인 Event는 원래 질문의 Work Item에 귀속한다.
  대기 검색은 같은 Case에서 동일 Attention ID를 기다리는 업무까지
  넓힌다. 다른 Attention과 다른 Case의 업무는 해소하지 않는다.
  Governance 승인 Event는 원래 action의 Work Item 범위를 유지한다.
  활성 MANAGER 검증, 일반 답변의 승인 승격 금지, 승인 시 구매 DONE은
  [인간 답변·구매 결정](14_human_purchase_api.md)의 계약을 유지한다.

## Run 시점과 기존 데이터

V27과 독립 DDL 19는 `runs.started_at`·`finished_at`을 TIMESTAMPTZ로
바꾼다. 기존 `claimed_at`·`lease_expires_at`과 같은 instant 타입을
사용한다. writer는 OffsetDateTime의 NOW를 쓰고 RunDto는 timezone을
재해석하지 않고 OffsetDateTime에서 Instant를 읽는다. Work Item과
다른 ERP 테이블의 TIMESTAMP 컬럼은 변경하지 않는다.

기존 두 컬럼의 값은 Asia/Seoul 벽시계 시각이라는 source의 전제를
현재 로컬 JDBC에서 확인했다. 2026-10-03 회귀 실행의 JVM과 JDBC
`SHOW TimeZone`은 모두 Asia/Seoul이었고 psql 기본 세션은 Etc/UTC였다.
수정 전 UTC 조회의 `claimed_at - started_at`은 약 -32,399초였다.
따라서 migration은 세션 기본값에 기대지 않고
`AT TIME ZONE 'Asia/Seoul'`로 기존 시점을 복원한다.

업그레이드 회귀는 V26의 `2026-10-03 09:00:00`과
`09:00:00.4`를 V27 뒤 각각 `00:00:00Z`와 `00:00:00.4Z`로 읽고
claimed 이후 소요 시간을 0.4초로 확인한다. UTC와 KST 조회가 같은
Instant·소요 시간을 반환하고 미완료 Run의 NULL finish도 유지한다.
기존 값이 다른 timezone에서 기록된 배포 DB에는 이 Asia/Seoul 전제를
그대로 적용할 수 없다. 이 검증은 현재 로컬 stack의 기록 기준이다.

## 이전 source의 읽기 계약과 #51 연결

#54 설명은 source #19에서 GET /monitor의 상태 변이와 ASK의
FINISHED_GOODS 누락을 이미 수정했다고 전제했다. 선택 이식한 현재
부모에는 두 수정이 빠져 있어 함께 반영했다. GET /monitor는 조회만
하고 Event·Work Item·Run·Attention을 생성하거나 바꾸지 않는다.
예약 대기와 후속 입고 관찰은 POST dispatch와 실행기 claim의
신뢰된 sweep 경로가 소유한다. ASK는 검색어 유무에 관계없이 완제품
stock만 반환하고 SEMI_FINISHED·INTERMEDIATE를 제외한다.

#51의 sweep hook과 관리 후속 업무 제외 조건은 부모에서 이미
이식했다. hook을 중복 추가하지 않았다. 납기 도래 후속 업무에 대해
monitor는 상태를 바꾸지 않고 신뢰된 sweep은 관찰 Event를 한 번
기록하며 업무를 WAITING으로 유지한다. 모델 Run을 예약하지 않고
변경 없는 반복 관찰은 조용하다.

## 검증 범위

수정 전 5개 결함의 회귀 실행에서 8개 실패를 기록했다. 잘못된 PO
답신이 대기를 해소했고, 위험 WAITING이 열림 수에서 빠졌으며, 글로벌
사실은 한 Case만, 동일 Attention 승인은 한 Work Item만 재개했다.
읽기 계약 2개도 수정 전 각각 실패를 확인했다.

검증은 PostgreSQL 통합 회귀, backend test/bootJar, MCP test,
V26→V27 데이터 보존, 빈 DB의 Flyway와 독립 DDL 비교로 구분한다.
실제 모델 실행·Auth0·다중 채널 UAT는 이 변경의 검증 범위가 아니다.

2026-10-03 검증에서 Java 21·PostgreSQL 18.6의
`./gradlew test bootJar --no-daemon`은 465개 test가 실패·skip 없이
통과했다. `npm test` 14개도 통과했다. V27 migration과 DDL 19는
같은 내용이며 V1~V26은 변경하지 않았다. 빈 DB의 schema 비교는 기존
`events.external_ref` 컬럼 순서만 정규화한 뒤 일치했다.
