# 재보충 데모 실행서

이 문서는 폐기용 PostgreSQL 18에 준비한 재보충 데이터를 사용하여, 같은 MANAGER가 ChatGPT와 Codex의 새 대화 사이에서 Case를 이어 보고 정확한 구매 제안을 결정하는 시연 절차다. **로컬 자동 시험, 읽기 전용 준비 검사, 실제 외부 인수는 별개다.** 준비 검사 통과만으로 실제 모델 실행·클라이언트 확인 UX를 완료했다고 기록하지 않는다. 실제 Auth0 로그인·OBO는 보류(#21·#22)다.

## 1. 실행 범위와 준비물

Java 21, Node.js 22 이상, Zig 0.16.0, 실행 중인 Docker와 PostgreSQL 18이 필요하다. 로컬 DB 조회에는 `psql`이 필요하다. 저장 계획·Run 계약은 [실행 API](13_execution_and_plan_api.md), 이미지·로그인은 [런타임 안내](14_cli_and_runtime.md)를 따른다. Auth0·OAuth 연결 설정은 보류(#21·#22)다.

실제 값은 gitignored 설정 파일이나 비밀값 주입 도구에 보관한다. 토큰·시크릿·DSN·사용자 subject 원문을 실행 결과나 인수 증거에 넣지 않는다. 운영 DB를 데모 대상으로 사용하지 않는다. 이 시연은 구매 제안·사람의 승인/반려·PO 적용·서버 관리 후속 대기까지 확인한다. 실제 생산·입고 변경을 실행하거나, 입고가 없는데 Case를 자동 해결하지 않는다.

| 프로세스 | 환경 변수 이름 |
|---|---|
| Backend | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `MULINO_WORKER_TOKEN` |
| 로컬 stdio MCP | `MULINO_LOCAL_ROLE`, `MULINO_API_BASE` |
| Runner | `MULINO_WORKER_TOKEN`, `MULINO_WORKER_ID`, `MULINO_API_BASE`, `MULINO_AGENT_API_URL`, `MULINO_AGENT_RUNTIME`, `MULINO_RUNTIME_IMAGE`, `MULINO_AUTH_VOLUME`, `MULINO_AGENT_MODEL` |
| 준비 검사 선택 입력 | DB 변수 3개 및 위 런타임 설정. `MULINO_CHATGPT_CIMD_URL`, `MULINO_CODEX_CIMD_URL`은 보류(#21·#22) |

각 프로세스에 필요한 값만 전달한다. 예제 설정은 [MCP](../mcp-server/.env.example)와 [Runner](../agents/runner/.env.example)에 있다. placeholder를 실제 값으로 교체한다.

## 2. 빌드와 로컬 회귀 시험

저장소 루트 기준으로 각 명령은 해당 디렉터리에서 실행한다. Backend 빌드의 jOOQ 생성은 별도 Docker PostgreSQL 18을 사용하며 실제 애플리케이션 DB를 사용하지 않는다.

```bash
cd backend
./gradlew clean test bootJar --no-daemon
```

```bash
cd mcp-server
npm ci
npm test
```

```bash
cd agents/cli
zig build
zig build test
node --test test/smoke.mjs
```

```bash
node --test scripts/demo/readiness.test.mjs
node --test agents/runner/test/*.test.js
node agents/runner/scripts/build-image.mjs
node agents/runner/scripts/smoke-image.mjs
```

명시적 opt-in 로컬 연결 시험은 Node, OpenSSL, `mcp-server`의 `npm ci`, Zig CLI 빌드가 준비된 상태에서 실행한다. 별도로 생성한 폐기용 DB 이름은 정확히 `mulino_demo_e2e`여야 하며, `DB_URL`은 `jdbc:postgresql://127.0.0.1:<포트>/mulino_demo_e2e` 또는 `localhost` 형식으로 명시한다. `DB_USERNAME`·`DB_PASSWORD`도 해당 시험 DB 자격 증명으로 주입한다. 시험은 `demo_e2e` 스키마를 초기화한다. 일반 애플리케이션 DB 설정을 그대로 재사용하지 않는다. 상세 전제와 실행 예시는 [로컬 E2E 안내](../mcp-server/scripts/demo-e2e/README.md)를 따른다.

```bash
cd backend
./gradlew demoE2eTest
```

이 시험은 로컬 역할 헤더·worker 토큰과 scripted model을 사용한다. 실제 외부 신원 제공자 로그인이나 유료 모델 호출을 증명하지 않는다. 실패·skip을 실제 외부 인수 성공으로 해석하지 않는다. 일반 `test`와 opt-in 시험 결과를 별도로 기록한다.

## 3. 폐기용 DB 초기화와 신원 연결 순서

1. 운영자가 이름·호스트를 확인한 **새 빈 폐기용 DB**를 만들고 Backend용 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 설정한다. readiness는 DB를 만들지 않는다. DB 목록·접속 기본값에 의존하지 말고 매 명령의 대상을 명시한다.
2. 그 DB로 Backend를 한 번 시작하여 Flyway를 최신 저장소 migration까지 적용하고 종료한다. 최신 버전은 `backend/src/main/resources/db/migration`의 파일을 기준으로 하며, 현재는 V25다.
3. [fixture 안내](../database/seed/replenishment_demo_README.md)에 따라 `database/seed/replenishment_demo.sql`을 적용한다. 이미 업무 데이터나 사용자가 있는 DB에서는 seed가 거절된다. 거절을 우회하거나 기존 행을 삭제하지 않는다.
4. Backend를 다시 시작한 뒤 `X-Mulino-Local-Role: MANAGER` 헤더로 `/me`를 호출하여 역할과 capability를 확인한다. PoC는 로컬 역할 헤더로 신원을 설정한다. 실제 Auth0/OBO 연결은 보류(#21·#22)다.

SQL 적용은 비밀번호를 argv에 넣지 않고 운영자의 연결 환경으로 전달한다. 아래 `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`는 확인한 폐기용 대상을 가리켜야 한다. `psql`의 로컬 기본 DB 선택에 의존하지 않는다.

```bash
psql -X -w -v ON_ERROR_STOP=1 --host="$PGHOST" --port="$PGPORT" \
  --username="$PGUSER" --dbname="$PGDATABASE" \
  --file=database/seed/replenishment_demo.sql
```

독립 스키마 검증에는 **다른 새 빈 DB**에서 `database/ddl/00`부터 번호 순서대로 raw DDL을 적용할 수 있다. raw DDL을 적용한 DB에 Flyway를 그대로 시작하지 않는다. 데모 애플리케이션 DB는 Flyway 경로를 사용한다. 준비 검사는 `flyway_schema_history`가 없는 raw DDL 전용 DB를 통과시키지 않는다.

fixture의 기준일은 **2026-09-05, Asia/Seoul**이다. 고정 업무 시계를 사용하는 자동 시험에서만 해당 기준일의 16,500원 후보를 기대한다. 실제 Backend는 현재 시각으로 수요·유효기간·납기를 판단하므로 다른 날의 라이브 시연에서 동일 수량·금액을 보장하지 않는다. 라이브 목표일과 실제 저장 계획의 근거를 함께 기록하며 기대값을 맞추려고 운영 시계를 변경하지 않는다.

## 4. 서비스 시작과 읽기 전용 준비 검사

시작 순서는 DB → Backend → loopback MCP → 고정 HTTPS 터널 → 원격 로그인 확인 → Runner다. Runner가 시작되면 실제 dispatch/claim과 모델·업무 쓰기가 진행될 수 있으므로 마지막에 시작한다.

1. DB 상태를 확인하고 Backend 디렉터리에서 `./gradlew bootRun`을 실행한다. `MULINO_WORKER_TOKEN`을 함께 설정한다.
2. 필요하면 MCP 디렉터리에서 `MULINO_LOCAL_ROLE`과 `MULINO_API_BASE`를 주입하고 `npm start`로 stdio MCP를 시작한다. 원격 HTTP transport는 보류(#22)다.
3. 저장소 루트에서 다음 점검을 실행한다. `.env`를 자동 탐색하지 않으므로 필요한 환경은 사전에 명시적으로 주입한다.

```bash
node scripts/demo/readiness.mjs
```

기본 동작은 점검뿐이다. 서비스/컨테이너 시작, migration/seed, tenant 변경, 토큰 획득, 로그인, 모델 요청, 업무 쓰기를 실행하지 않는다. DB 변수 3개가 모두 있을 때만 지정 PostgreSQL에 `BEGIN READ ONLY`와 읽기 전용 startup 옵션·statement timeout을 적용해 PostgreSQL 18, 최신 Flyway version, 실패 migration 유무를 조회한다. 접속 URL의 사용자정보·query·fragment는 거부한다. 원격 DB는 인증서 검증 TLS를 요구하고 비밀번호는 자식 프로세스 환경으로만 전달한다.

HTTP는 지정된 안전한 URL에 GET만 보내며 redirect를 따라가지 않는다. public origin/issuer/CIMD는 HTTPS를 요구한다. issuer는 실제 MCP 시작 설정과 동일한 정규 문자열(마지막 `/` 포함)이어야 하며, 다르면 issuer/resource 조회 전에 실패한다. MCP 설정 자체도 실제 시작 검증 함수를 사용한다. 인간 token은 명시한 HTTPS 또는 loopback Backend `/me`에만 전송한다. 응답 크기와 시간, 자식 명령 실행 시간에 제한을 둔다. 제공자 오류 본문·환경 값은 결과에 출력하지 않는다.

결과의 각 component는 `PASS`, `MISSING`, `FAILED`, `UNVERIFIED`로 구분한다. `MISSING`은 필요한 변수 이름이나 설치 확인 실패, `FAILED`는 버전/응답/연결/안전성 조건 불일치, `UNVERIFIED`는 이 도구로 증명하지 않는 외부 인수다. `PASS`인 로그인 볼륨은 존재만 확인한 것이다. 이미지 존재도 내용·로그인·실행 성공을 대신하지 않는다.

종료 코드 `0`은 설치·설정 점검 통과, `2`는 미충족 조건, `1`은 명령 사용/예상 밖 오류다. **모든 설정 점검이 통과해도 `ready=false`, `liveAcceptance=UNVERIFIED`를 유지한다.** `softwareConfigurationChecksPassed`를 실제 업무 완료로 사용하지 않는다.

Auth0 discovery·CIMD·OBO 검사는 보류(#21·#22)다. readiness는 DB·backend 연결과 소프트웨어 버전만 점검한다.

## 5. Codex 로그인과 실제 모델 실행

[런타임 안내](14_cli_and_runtime.md)의 전용 volume 생성 및 로그인 명령을 사용해 사람이 해당 런타임에 로그인한다. Codex는 `login --device-auth`, Claude Code는 `claude login`을 사용한다. 호스트 로그인 파일을 복사하지 않는다. 준비 검사의 volume PASS를 로그인 증거로 대신하지 않는다. 계정에서 실제 사용할 수 있는 `MULINO_AGENT_MODEL`을 명시하며 모델을 추측해서 자동 선택하지 않는다.

ChatGPT와 Codex의 실제 원격 MCP 연결·로그인(Auth0 OAuth)은 보류(#21·#22)다. 로컬 PoC에서는 `MULINO_LOCAL_ROLE` 환경 변수로 역할을 지정해 stdio MCP를 사용한다.

Runner 디렉터리의 gitignored `.env`를 준비하고 실행한다.

```bash
cd agents/runner
node --env-file=.env src/main.js
```

Runner는 worker 토큰(static bearer)을 사용하고 각 모델 Run에는 scoped capability를 전달한다. worker token으로 인간 구매 결정을 대체하지 않는다. 실제 모델의 역할 수행·CLI 호출·저장 계획 참조·승인 대기 종료가 관찰되어야 라이브 모델 실행 항목을 완료로 기록한다.

## 6. 두 클라이언트에서 업무 이어가기

1. ChatGPT에서 MANAGER의 재보충 목표와 거점·SKU·목표일을 명시해 Case를 생성한다. Case 참조를 기록한다. Runner가 저장 계획과 구매 제안을 만들고 인간 결정 Attention으로 대기하는지 확인한다.
2. Codex의 **새 대화**에서 같은 MANAGER로 로그인하여 Case 목록/검색과 overview로 해당 업무를 찾는다. 기존 대화 맥락 없이 목표·계획·제안·대기 이유·다음 행동을 읽을 수 있어야 한다.
3. 구매 선택을 보여줄 때마다 **정확한 proposal version과 hash**, 품목·수량·금액·근거를 함께 확인한다. 사람이 승인 또는 반려와 사유를 명시하고, 도구를 실행할 때 클라이언트의 개별 확인을 받는다. 전역 자동 승인으로 변경하지 않는다. 새 version/hash가 생기면 다시 읽고 확인한다.
4. ChatGPT로 돌아와 같은 Case를 조회한다. 승인했다면 실제 PO 참조·결정 감사 기록·서버 후속 입고 대기를 확인한다. 반려했다면 반려 상태·사유와 다음 행동을 확인한다. 같은 결정을 재전송하여 중복 PO가 생기게 하지 않는다.
5. 반대 방향도 **별도 목표 또는 별도 폐기용 fixture 환경**에서 수행한다. Codex에서 생성 → ChatGPT 새 대화에서 조회/명시적 결정 → Codex에서 결과 확인 순서다. 같은 활성 목표는 기존 Case로 연결될 수 있으므로 단순 재입력으로 독립 시나리오가 생긴다고 가정하지 않는다.
6. 다른 버전/hash의 결정이 거부되는지, VIEWER의 쓰기·결정이 거절되는지, 사용자가 도구 확인을 취소했을 때 쓰기가 없는지 확인한다. 로컬 시험에서 확인한 계약과 실제 클라이언트 UX 증거를 구분한다.

PO 적용 뒤 Case가 `WAITING`으로 남고 서버 관리 후속 업무가 입고를 관찰하는 것은 정상이다. 입고·생산을 가짜로 입력하여 Case를 해결하지 않는다. 실제 운영 쓰기와 Case 자동 해결은 이 데모의 인수 범위 밖이다.

## 7. 중단과 실패 보존

실패 시 Runner를 먼저 중단하여 새 Run·모델·업무 쓰기를 멈춘다. 추가 승인/재시도를 멈추고 Case·Run·Attention·proposal version/hash·PO·감사 기록을 읽어 현재 상태를 확인한다. 응답 유실만으로 같은 업무를 새 키로 다시 생성하지 않는다. 민감값을 제거한 오류 종류·참조·시각을 보관한다.

정상 종료도 Runner에 SIGINT/SIGTERM → HTTPS 터널 → MCP → Backend 순서로 수행한다. 프로세스 종료와 실행 중 Run/컨테이너 정리 결과를 확인한다. DB와 전용 로그인 volume은 재개·감사를 위해 보존한다. 실제 DB를 삭제·truncate하거나 PO/감사 기록을 지워 재시험하지 않는다. 새 fixture가 필요하면 운영자가 구분한 새로운 폐기용 DB를 사용한다.

## 8. 인수 기록 체크리스트

다음은 서로 대체할 수 없는 증거다. 실제 미수행 항목은 미검증으로 남긴다.

- [ ] 로컬 Backend/MCP/CLI/Runner·readiness 회귀 시험과 opt-in `demoE2eTest`의 실행 결과를 기록했다.
- [ ] 명시한 폐기용 PostgreSQL 18과 최신 Flyway·fixture 선행·이후 ERP 신원 mapping을 확인했다.
- [ ] readiness JSON의 미충족 조건을 해소했으며 volume/image PASS의 한계를 기록했다.
- [ ] (보류 #21·#22) 실제 Auth0 tenant/CIMD/사용자 permission/connection 및 OBO 신원 보존을 확인했다.
- [ ] (보류 #21·#22) ChatGPT와 Codex 각각 실제 OAuth 로그인·만료 후 갱신, 같은 MANAGER `whoami`를 확인했다.
- [ ] 전용 Codex volume에서 수동 로그인하고 명시한 실제 모델의 scoped 역할 수행을 관찰했다.
- [ ] ChatGPT → Codex 새 대화 → ChatGPT 결과 확인을 실제 수행했다.
- [ ] Codex → ChatGPT 새 대화 → Codex 결과 확인을 실제 수행했다.
- [ ] 매 결정의 정확한 version/hash·사유·사람의 개별 확인/취소 UX와 권한 거절 증거를 남겼다.
- [ ] 실제 PO·감사 기록·후속 WAITING을 확인하고 생산/입고 쓰기·Case 자동 해결 없이 중단했다.
- [ ] fixture 고정 기준일과 라이브 현재 시각을 구분하고 시연 시각·계획 근거를 기록했다.

증거에는 도구/시험 이름, 실행 날짜, 비밀값 없는 Case/Run/제안/PO 참조, 결과와 남은 제한을 남긴다. 토큰·시크릿·개인 계정 식별 원문이나 제공자 오류 본문은 남기지 않는다.

## 9. 2026-09-12 준비 상태

[검토 및 인수 기록](reviews/2026-09-12-pr18-pr19.md)에 이번 코드 검토·실행 결과를 기록했다. 전용 Codex 로그인과 0.154.0 이미지 검사는 성공했지만 실제 Astra 요청은 워크스페이스 크레딧 부족으로 종료됐다. Auth0 tenant·두 클라이언트 연결은 보류(#21·#22)이며 모델 역할 수행은 미완료다. ngrok 로그인·고정 주소와 실제 HTTPS 전달 검증은 아래 추가 기록에서 완료했다. 이미 확인한 로컬 시험 결과로 이 항목들을 대신 체크하지 않는다.

승인안 조회는 원래 계획의 계산 근거·재고/예상 공급·경고·미조치 영향을 함께 표시한다. 자료가 이후 바뀌어도 저장된 과거 근거를 현재 사실로 바꾸지 않는다. 실제 결정 직전 최신 버전/hash를 다시 읽고 인간의 개별 선택을 확인한다.

### ngrok 연결 완료

2026-09-12 Safari에서 기존 ngrok 계정에 로그인하고 기존 dev domain `https://basilar-mertie-rachiform.ngrok-free.dev`를 확인했다. 로컬 ngrok 3.39.11의 `mulino-mcp` 엔드포인트는 이 주소를 `http://127.0.0.1:3001`로 연결한다. 기본 ngrok 설정 파일에 기존 인증 토큰을 저장하고 파일 권한 0600과 `ngrok config check` 통과를 확인했다. 토큰은 저장소에 넣지 않는다.

임시 확인 서버의 고유 응답을 공개 HTTPS 주소에서 200으로 수신하여 실제 전달을 검증한 뒤 확인 서버를 종료했다. ngrok 에이전트는 연결 상태로 유지하며 실제 원격 MCP는 #21·#22 완료 후 시작한다. 이 결과는 MCP OAuth/OBO 인수 완료를 뜻하지 않는다. 데모의 공개 주소 변수는 로컬 `~/.config/mulino/demo-20260912/ngrok.env`에 저장했다. ngrok를 종료한 뒤에는 `ngrok start mulino-mcp`로 다시 시작한다.
