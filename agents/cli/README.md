# mulino CLI

Zig 0.16.0 표준 라이브러리만 사용하는 agent용 API 클라이언트입니다. 런타임에 Node·curl·별도 HTTP 라이브러리가 필요하지 않습니다. 서버가 계산·권한·업무 상태를 검증하며 CLI는 JSON을 전달합니다.

## 빌드와 테스트

```bash
zig build
zig build test
node --test test/smoke.mjs
```

호스트 실행 파일은 `zig-out/bin/mulino`입니다. Node는 실제 자식 프로세스와 loopback HTTP 서버로 실행하는 테스트에만 사용합니다. TLS 거부 시험은 테스트용 OpenSSL로 임시 자체 서명 인증서를 만들고 시험 후 삭제합니다. Node와 OpenSSL은 CLI 런타임 의존성이 아닙니다. 다른 실행 파일을 시험하려면 `MULINO_TEST_BINARY`에 경로를 지정합니다. `build.zig.zon`의 최소 Zig 버전은 0.16.0으로 고정합니다.

Docker용 정적 Linux 실행 파일을 별도로 만들 수 있습니다.

```bash
zig build -Dtarget=aarch64-linux-musl -Doptimize=ReleaseSafe --prefix zig-out/linux-arm64
zig build -Dtarget=x86_64-linux-musl -Doptimize=ReleaseSafe --prefix zig-out/linux-amd64
```

각 prefix의 `bin/mulino`가 산출물입니다. 컨테이너에는 해당 아키텍처의 실행 파일과 TLS 검증에 필요한 시스템 CA 인증서 저장소가 있어야 합니다. 호스트 테스트·cross build는 실제 외부 신원 제공자 연결이나 Docker 안의 CLI 실행 성공을 대신 증명하지 않습니다.

## 환경 설정

| 변수 | 계약 |
|---|---|
| `MULINO_API_URL` | 필수 API base URL. 예: `http://host.docker.internal:8080/api/v1` |
| `MULINO_TOKEN` | 필수 Run capability. 인증·역할을 임의로 지정하는 입력은 없음 |
| `MULINO_API_TIMEOUT_MS` | 선택 전송 제한 시간. 기본 10000ms, 1~60000ms |

HTTPS는 시스템 CA와 서버 인증서를 검증합니다. 인증서 검증을 끄는 설정은 없습니다. 평문 HTTP는 `localhost`, `127.0.0.1`, `::1`, 컨테이너용 `host.docker.internal`에만 허용합니다. URL의 사용자 정보·query·fragment·상위 경로 이동은 거부합니다. 환경변수 값·토큰·서버 오류 본문을 로그에 노출하지 않습니다.

## 현재 지원 명령

```text
mulino material show ID
mulino po show ID
mulino po propose PLAN_REF --json '{}' --request-key KEY
mulino case show CASE_REF
mulino plan show PLAN_REF
mulino plan calculate CASE_REF --json BODY --request-key KEY
mulino work create --json BODY --request-key KEY
mulino work transition WORK_ITEM_REF --json BODY --request-key KEY
```

| 명령 | HTTP 경로 |
|---|---|
| `material show` | `GET /agent/materials/{id}` |
| `po show` | `GET /agent/purchase-orders/{id}` |
| `po propose` | `POST /plans/{ref}/purchase-proposal` |
| `case show` | `GET /agent/cases/{ref}` |
| `plan show` | `GET /agent/plans/{ref}` |
| `plan calculate` | `POST /cases/{ref}/plans` |
| `work create` | `POST /agent/work-items` |
| `work transition` | `POST /agent/work-items/{ref}/transition` |

경로는 `MULINO_API_URL` 뒤에 붙습니다. 참조의 특수문자는 하나의 경로 구성요소 안에서 percent-encode합니다. 쓰기는 1~200자 ASCII `--request-key`가 필수이며 `Idempotency-Key` 헤더로 그대로 전달합니다. JSON 본문은 object여야 하고 256 KiB 이내입니다. CLI는 필드별 업무 검증을 중복하지 않습니다.

요청 DTO의 현재 필드는 다음과 같습니다. 서버가 인가한 역할과 범위 안에서만 처리합니다.

- `po propose`: 빈 JSON object `{}`만 전달합니다. 서버가 해당 plan의 모든 구매 행을 선택하며 품목·수량·공급자를 CLI에서 변경하지 않습니다. 빈 object 여부는 서버가 검증합니다.
- `plan calculate`: `warehouseId`, `productIds`, 선택 `horizonDays`.
- `work create`: `caseRef`, `agentKey`, `title`, 선택 `description`, `metadata`. 현재 Orchestrator가 담당 역할에 업무를 배정하는 경로입니다.
- `work transition`: `outcome`, `summary`, 선택 `waitingConditions`. 대기 조건의 형식과 완료 근거는 서버가 검증합니다.

예를 들어 `plan calculate`는 아래처럼 호출합니다.

```bash
mulino plan calculate CASE-EXAMPLE \
  --json '{"warehouseId":1,"productIds":[1,2],"horizonDays":30}' \
  --request-key plan-example-1
```

`po approve`·승인 결정·구매 적용 명령은 지원하지 않습니다. `material show`·`po show`는 서버가 Run capability 범위와 역할을 검증하는 조회입니다. 성공을 가장한 응답이나 직접 DB 접근은 제공하지 않습니다.

## 출력과 실패 처리

성공하면 서버 JSON 원문을 stdout에 전달하고 종료 코드 0을 반환합니다. 소수·큰 정수는 계산하거나 재직렬화하지 않으므로 원래 숫자를 보존합니다. `PENDING_APPROVAL`·`BLOCKED` 같은 업무 판정도 HTTP 성공 응답이면 종료 코드 0의 데이터입니다.

`po propose`가 `PENDING_APPROVAL` 또는 구매 불필요 결과를 반환하면서 Run capability를 종료할 수 있습니다. 이는 성공이며 자동 재시도하거나 추가 완료 명령을 보내지 않습니다. 승인 결정과 실제 PO 적용은 서버의 별도 권한 경로에서 처리합니다.

사용법·환경 설정 오류는 종료 코드 1, HTTP·네트워크·TLS·timeout·응답 오류는 종료 코드 2이며 stderr에 안전한 JSON만 출력합니다. HTTP 오류에는 상태 번호가 포함됩니다. 예: `{"error":"API_ERROR","status":409}`. 실패 시 stdout에는 성공 결과를 내보내지 않습니다.

응답은 1 MiB 이내의 유효 JSON이어야 합니다. 서버가 capability를 문자열·JSON escape로 반사하면 응답 전체를 안전한 오류로 거부합니다. 일부를 재직렬화하여 금액 정밀도를 바꾸거나 토큰을 출력하지 않습니다.

리다이렉트는 따라가지 않으며 HTTP 요청을 자동 재시도하지 않습니다. Timeout 뒤에는 서버에서 쓰기가 이미 반영되었을 수 있습니다. 호출 agent가 현재 상태를 확인하고 같은 내용의 재시도가 필요하다고 판단한 경우 같은 요청 키를 재사용합니다. 업무·Run을 완료한 뒤 capability가 폐기되면 후속 API 호출이 실패하는 것이 정상입니다.

전송 구현은 `std.http.Client.request`를 사용합니다. Zig 0.16.0의 고수준 `fetch`는 부분 chunked 본문 수신 중 취소되면 없는 body error를 강제 해제하여 비정상 종료할 수 있습니다. CLI는 이 경로를 피하고 body error를 안전하게 처리하며 연결을 닫습니다. 실제 부분 본문 정지 시험에서도 timeout JSON과 종료 코드 2를 유지합니다.
