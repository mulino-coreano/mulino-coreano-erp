# Auth0 데모 설정 도구

Node.js 22 이상에서 별도 npm 의존성 없이 실행한다. 실제 tenant에 적용하기 전에 생성 계획과 조회 결과를 검토한다. 기본 동작은 GET만 수행하며 `--apply`가 있어야 Auth0 설정을 변경한다. 이 도구는 tenant 생성·과금·로그인·사용자 생성·ERP 역할 배정을 수행하지 않는다.

```bash
node --test scripts/auth0/setup.test.mjs
node scripts/auth0/setup.mjs --help
node scripts/auth0/setup.mjs --plan
node scripts/auth0/setup.mjs
```

`--plan`은 네트워크와 Management API token 없이 목표 설정을 JSON으로 보여준다. 기본 실행은 공개 metadata와, token이 제공된 경우, tenant/API/client/grant를 조회해 변경 예정 항목을 보여준다. 이미 맞는 객체는 `unchanged`로 표시한다. 실제 secret은 계획·JSON 결과·오류에 포함하지 않는다.

## 환경 설정

| 변수 | 값과 용도 |
|---|---|
| `MULINO_AUTH_ISSUER` | 로그인에 사용하는 Auth0 issuer. 예: `https://tenant-name.us.auth0.com/`. 루트 경로와 HTTPS만 허용하며 trailing slash는 정규화한다. |
| `MULINO_PUBLIC_ORIGIN` | ngrok 고정 HTTPS origin. 경로를 넣지 않는다. |
| `MULINO_MCP_AUDIENCE` | 생략 시 `${MULINO_PUBLIC_ORIGIN}/mcp`. 다른 값은 거부한다. |
| `MULINO_API_AUDIENCE` | 생략 시 `urn:mulino:erp-api`. 이 데모에서는 이 값만 허용한다. |
| `AUTH0_MANAGEMENT_ORIGIN` | 선택. Management API의 HTTPS tenant origin. custom-domain issuer와 관리 API 주소가 다른 경우 명시한다. 기본값은 issuer origin이다. |
| `AUTH0_MANAGEMENT_TOKEN` | 설정 프로세스 전용 Management API access token. MCP·백엔드·실행기·모델 환경으로 전달하지 않는다. |
| `MULINO_CHATGPT_CIMD_URL` | 실제 ChatGPT 연결 흐름에서 확인한 HTTPS client metadata URL. |
| `MULINO_CODEX_CIMD_URL` | 실제 Codex 버전의 연결 흐름에서 확인한 HTTPS client metadata URL. |

CIMD URL은 callback URL이 아니다. 해당 클라이언트가 제공하는 실제 URL을 사용한다. 도구는 URL을 추측하거나 공유 client secret으로 CIMD를 대체하지 않는다. 두 URL이 빠진 경우 나머지 tenant 설정을 준비할 수 있지만 준비 완료로 보고하지 않는다. 설치한 클라이언트가 CIMD를 제공하지 않으면 그 클라이언트 연결은 미검증/미지원으로 남기고 인증을 우회하지 않는다.

token은 셸 기록이나 명령행 인자에 적지 말고 운영자가 관리하는 비밀값 주입 방식으로 설정 프로세스에만 제공한다. 도구는 `.env`를 자동 탐색·로드하거나 현재 환경 전체를 출력하지 않는다. 설정 파일을 사용하면 비밀 파일을 Git에 추가하지 않는다.

Management API 조회에는 `read:tenant_settings`, `read:resource_servers`, `read:clients`, `read:client_grants`가 필요하다. 적용에는 해당 객체의 `create`/`update` 권한과 `update:tenant_settings`가 추가로 필요하다. 기존 client의 자격증명을 새 출력 경로로 복구할 때만 `read:client_keys`가 필요하다. `delete:*` 권한은 사용하지 않는다. CIMD 등록 및 OBO 기능에 대한 tenant 사용 가능 여부도 별도로 충족해야 한다.

## 적용과 자격증명 보관

환경 변수를 준비한 설정 프로세스에서 다음을 실행한다.

```bash
node scripts/auth0/setup.mjs --apply --credentials-dir "$HOME/.config/mulino/auth0"
```

적용 전 issuer·OAuth endpoint·Authorization Code·PKCE S256·지정한 CIMD 문서를 점검한다. 이 필수 검사가 실패하면 원격 변경을 시작하지 않는다. CIMD/issuer-response tenant 스위치 및 공개 MCP metadata는 아직 준비 중일 수 있으므로 적용 후 다시 검사한다.

다음 설정을 생성하거나 차이만 PATCH한다.

| 대상 | 설정 |
|---|---|
| Tenant | `resource_parameter_profile=compatibility`, `client_id_metadata_document_supported=true`, `authorization_response_iss_parameter_supported=true` |
| MCP API | 공개 `/mcp` audience, RS256, RFC 9068 access token, RBAC, 인간 scope 3개, 명시적 client grant 필요, `allow_offline_access=true` |
| ERP API | `urn:mulino:erp-api`, 같은 서명·권한 정책, 인간 scope와 `worker:dispatch` |
| MCP OBO client | `app_type=resource_server`, 공개 MCP API에 연결, `on_behalf_of_token_exchange` 허용, ERP audience의 `subject_type=user` grant |
| Worker client | `app_type=non_interactive`, `client_credentials`만 허용, ERP audience의 `subject_type=client` grant에 `worker:dispatch`만 부여 |
| ChatGPT·Codex CIMD client | 지정된 각 URL을 Auth0의 manual CIMD 등록 endpoint로 등록, MCP API에 인간 scope의 user grant만 부여 |

인간 scope는 `erp:read`, `work:write`, `procurement:decide`다. 사용자별 허용 권한과 ERP 역할은 아래 수동 준비 단계에서 제한한다. 기존 API의 다른 scope 정의와 client metadata는 보존한다. 이 도구가 관리하는 grant의 scope는 위 범위로 맞추며 `allow_all_scopes=false`를 적용한다. 공유 API를 관리하던 tenant에서는 `--plan`과 기본 조회 결과를 먼저 검토한다.

API는 audience, OBO client는 `resource_server_identifier`, worker는 `mulino_role`/`mulino_resource` metadata, CIMD client는 `external_client_id`, grant는 client/audience/subject type으로 식별한다. 목록은 페이지 단위로 모두 조회한다. 중복 객체·같은 이름의 다른 client는 임의 선택하지 않고 오류로 반환한다. 이미 등록된 CIMD 문서는 자동 동기화하지 않는다. upstream 문서의 변경 동기화는 Auth0에서 별도 검토한다.

적용 시 `mcp.credentials.json`과 `worker.credentials.json`에 각각 `{issuer, client_id, client_secret}`을 저장한다. 파일은 독점 생성하고 `0600`으로 제한하며, stderr에는 저장 경로만 출력한다. 새 디렉터리는 `0700`으로 만든다. MCP 파일의 `client_id`/`client_secret`은 런타임의 `MULINO_AUTH0_CLIENT_ID`/`MULINO_AUTH0_CLIENT_SECRET`에 각각 주입한다. worker 파일은 실행기 전용 자격증명 저장소에 넣고 MCP나 모델에 전달하지 않는다.

재실행 시 기존 파일은 issuer와 client ID, 파일 형식·권한을 검사하고 덮어쓰지 않는다. 기존 파일을 보유하면 secret을 다시 요청하지 않는다. 새 출력 디렉터리로 실행하면 기존 client를 찾은 후 secret을 조회해 저장할 수 있지만 회전시키지는 않는다.

원격 설정에는 전체 트랜잭션이나 자동 rollback이 없다. 중간 실패 후 같은 명령을 재실행하면 성공한 객체를 재사용한다. 응답 유실 시에도 재조회한 identifier로 이어서 처리한다. secret 저장 중 실패하면 빈/부분 파일이 남을 수 있다. 기존 Auth0 client 상태를 확인하고 보관 경로를 검토한 뒤 새 빈 디렉터리로 재실행한다. 읽을 수 있는 비밀 파일을 덮어쓰거나 client를 삭제해 복구하지 않는다. 같은 tenant에 설정 프로세스를 동시에 실행하지 않는다.

## 준비 완료 판정의 범위

종료 코드는 `0`(오프라인 계획 생성 또는 설정/metadata 점검 통과), `2`(실행은 끝났지만 준비 조건 미충족), `1`(설정·API·파일 오류)이다. 적용 후 원격 설정을 다시 GET하여 반영 여부를 확인한다. `--apply`가 `2`로 종료되어도 원격 설정은 일부 또는 모두 반영됐을 수 있다. JSON의 `actions`, `remainingActions`, `checks`를 함께 확인한다.

검사는 issuer discovery, HTTPS OAuth/JWKS endpoint, Authorization Code 및 PKCE S256 광고, CIMD·authorization response issuer 지원, 공개 `/.well-known/oauth-protected-resource/mcp`의 resource/issuer/scopes, 제공된 각 CIMD 문서, resource compatibility 및 tenant 객체 설정을 확인한다. 현재 인간 대화 MCP가 공개하는 필수 scope는 `erp:read`, `work:write`, `procurement:decide`, `offline_access`다. 구매 결정 도구의 `procurement:decide`가 공개 metadata에 없으면 Auth0에 해당 권한이 준비되어 있어도 `ready=false`로 판정한다. redirect를 따라가지 않으며 공개 metadata에는 Management API token을 보내지 않는다. OBO가 discovery에 표준 필드로 광고된다고 가정하지 않는다.

갱신 준비를 위해 MCP API에만 `allow_offline_access=true`를 적용하고 읽어서 확인한다. `offline_access`는 OAuth 갱신 요청용 scope이므로 ERP 업무 permission 목록에 추가하지 않는다. issuer discovery의 `grant_types_supported`, 각 CIMD 문서의 `grant_types`, Auth0에 실제 등록된 각 CIMD client의 `grant_types`가 `refresh_token`을 포함하는지 모두 검사한다. 빠진 조건이 있으면 다른 설정을 적용하더라도 `ready=false`이며 종료 코드는 `2`다. JSON의 실패 항목과 `manualSteps`에 따라 제공자 문서 지원 여부를 확인하고 Auth0에서 CIMD 문서를 검토·동기화한다. 제공자가 갱신을 지원하지 않으면 갱신 미지원 상태를 그대로 남긴다. 등록 앱의 허용 grant를 문서와 무관하게 강제로 늘리지 않는다.

`ready=true`는 설정과 metadata의 준비 상태다. 실제 OAuth 연결·OBO 교환·사용자 권한·승인 UX 성공을 뜻하지 않는다. 다음 절차를 별도로 완료해야 1단계의 연결 시험을 완료했다고 기록할 수 있다.

1. Auth0 Dashboard에서 CIMD 앱에 실제 로그인 connection을 허용한다. 필요한 사용자에게 **두 API**의 허용 permission을 배정한다. 두 API 모두 RBAC를 켜므로 client grant만으로 사용자 scope가 생기지 않는다.
2. Auth0 `(issuer, subject)`를 ERP의 기존 활성 사용자에 사전 연결한다. 이메일 자동 매칭이나 Auth0 role의 ERP role 자동 승격을 하지 않는다.
3. ChatGPT와 Codex 각각에서 `offline_access`를 요청하는 OAuth 연결을 새로 시작하고, 만료 후 실제 token 갱신을 시험한다. 사용자 토큰의 MCP audience, 교환 토큰의 ERP audience와 같은 사용자 subject, 승인에 필요한 scope를 확인한다. token 갱신과 재인증 필요 상황을 각각 기록하되 토큰 본문이나 비밀값을 결과 문서에 기록하지 않는다.
4. 연결 기능이 tenant/클라이언트에서 지원되지 않으면 설정 오류로 남긴다. 실제 OBO 교환이 성공해야 OBO 지원을 확인했다고 기록한다. worker token으로 사용자 요청을 대체하지 않는다.
5. 각 대화 클라이언트에서 결정 도구를 호출할 때마다 확인하는 UX와 거절 흐름을 시험한다. 이 설정 도구는 해당 클라이언트 UI를 변경하지 않는다.

## 검증과 공식 자료

`setup.test.mjs`는 로컬 loopback HTTP 서버로 Auth0 Management API 및 공개 metadata 응답을 재현한다. 테스트는 실제 계정·token·네트워크 설정을 읽거나 변경하지 않는다. 조회 전용 실행, idempotency, 실패·응답 유실 후 재실행, 0600 파일 및 덮어쓰기 방지, 비밀값 비노출, 잘못된 issuer/PKCE/CIMD, 공개 resource 불일치·구매 결정 scope 누락, pagination, 응답 배열 순서 변경, 설정 반영 누락과 CLI 출력을 검증한다. 갱신 회귀 테스트는 기존 API의 offline access 복구와 재실행, issuer·공개 metadata·호스팅 CIMD·등록 앱 중 갱신 지원이 빠진 경우의 미완료 판정을 검증한다. 실제 tenant와 ChatGPT/Codex 연결은 이 자동 테스트 범위에 포함되지 않는다.

구현 필드는 다음 공식 문서를 기준으로 확인했다(2026-09-05).

- [Auth0 MCP OBO 구성](https://auth0.com/ai/docs/mcp/get-started/call-your-apis-on-users-behalf)
- [OBO user-delegated grant와 token exchange](https://auth0.com/docs/secure/call-apis-on-users-behalf/on-behalf-of-token-exchange)
- [Resource Parameter Compatibility Profile과 issuer response 설정](https://auth0.com/ai/docs/mcp/guides/resource-param-compatibility-profile)
- [Tenant settings API](https://auth0.com/docs/api/management/v2/tenants/patch-settings)
- [Manual CIMD 등록](https://auth0.com/docs/get-started/auth0-overview/create-applications/register-applications-with-cimd), [등록 API](https://auth0.com/docs/api/management/v2/clients/post-clients-cimd-register)
- [Client 목록과 projection/pagination](https://auth0.com/docs/api/management/v2/clients/get-clients), [Client 생성](https://auth0.com/docs/api/management/v2/clients/post-clients)
- [Resource server 생성](https://auth0.com/docs/api/management/v2/resource-servers/post-resource-servers), [Client grant 생성](https://auth0.com/docs/api/management/v2/client-grants/post-client-grants), [Client grant 갱신](https://auth0.com/docs/api/management/v2/client-grants/patch-client-grants-by-id)
