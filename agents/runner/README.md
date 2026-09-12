# Mulino 실행기

Node 22 이상에서 동작하는 호스트 프로세스입니다. Auth0 M2M 토큰으로 Run을 가져오고, 각 Run을 별도 Docker 컨테이너에서 실행하며 lease와 종료 상태를 백엔드에 전달합니다. 업무 계산·DB 변경·업무 완료 판정은 백엔드가 담당합니다.

자동 검증은 실제 Node 자식 프로세스, loopback HTTP 서버, 주입한 시계로 수행합니다. 추가로 고정 버전 Codex 이미지의 Linux CLI·파일·권한·취소를 실제 Docker에서 시험했습니다. Auth0 tenant, Codex 모델 업무 수행과 ChatGPT/Codex 로그인은 아직 검증하지 않았습니다. 이미지 빌드·별도 로그인 준비·검증 경계는 [CLI·런타임 안내](../../docs/14_cli_and_runtime.md)를 따릅니다.

## 실행 준비

1. 백엔드의 `/api/v1/internal/runs/claim`, `/heartbeat`, `/finish`와 `worker:dispatch` 권한을 준비합니다.
2. Auth0 M2M 애플리케이션에 ERP audience `urn:mulino:erp-api`와 `worker:dispatch` scope를 부여합니다. 사용자 로그인 자격증명과 분리합니다.
3. `.env.example`을 참고해 로컬 `.env`를 작성합니다. issuer는 HTTPS이고, 평문 백엔드 URL은 loopback만 허용합니다. 호스트 실행기의 `MULINO_AGENT_API_URL`에는 컨테이너가 접근할 `host.docker.internal`도 허용하며, 검증한 주소를 컨테이너 안의 `MULINO_API_URL`로 전달합니다.
4. 서로 다른 실행기는 서로 다른 `MULINO_WORKER_ID`를 사용합니다. 같은 ID로 중복 실행하면 백엔드가 추가 claim을 409로 거부합니다.
5. 아래 이미지 계약에 맞는 전용 이미지와 전용 Codex 로그인 named volume을 준비한 뒤 실행합니다.

```bash
npm test
node --env-file=.env src/main.js
```

런타임 외부 의존성은 Node 표준 라이브러리뿐입니다. SIGTERM/SIGINT는 새 claim을 중단하고 활성 프로세스와 Docker 컨테이너를 종료한 뒤, lease가 남아 있으면 ABORTED를 전달합니다.

## 런타임 이미지 계약

`node scripts/build-image.mjs`는 기본적으로 `mulino-codex-runtime:0.154.0` 이미지를 만듭니다. Codex 0.154.0, Linux 정적 `mulino`, 역할 스킬, CA 인증서와 `/opt/mulino/result.schema.json`을 포함합니다. `node scripts/smoke-image.mjs`는 네트워크를 차단하고 임시 로그인 볼륨으로 물리적 격리·CLI·설정·취소를 확인합니다. 실제 실행에는 전용 로그인 볼륨과 `MULINO_CODEX_MODEL` 설정이 필요합니다.

실행기는 읽기 전용 root filesystem, `/work`·`/tmp` tmpfs, 일반 사용자, 모든 Linux capability 제거, `no-new-privileges`, PID·메모리·CPU 제한을 적용합니다. 유일한 mount는 `MULINO_CODEX_AUTH_VOLUME` named volume입니다. 사용자 홈·저장소·Docker socket을 mount하지 않습니다. 전용 볼륨에는 해당 실행 환경의 Codex 로그인만 준비합니다. `--strict-config`, `--ignore-user-config`, `--ignore-rules`, `mcp_servers={}`와 고정 역할 지침을 적용합니다. 중첩 bwrap의 namespace 권한 오류를 실제 확인하여 Docker를 격리 경계로 삼고 내부 Codex는 `--sandbox=danger-full-access`로 실행합니다. 호스트에서 이 설정을 직접 실행하거나 Docker를 privileged로 바꾸는 경로는 없습니다. 신규 `codex exec --ephemeral --json --output-schema ... -`에 claim 맥락을 stdin으로 전달합니다.

Docker CLI에는 `MULINO_TOKEN`을 환경변수 이름으로 전달하므로 capability가 명령행 인수에 나타나지 않습니다. 이미지의 Linux `mulino`는 기존 CLI 계약대로 `MULINO_API_URL`과 `MULINO_TOKEN`을 읽습니다. 호스트 설정 `MULINO_AGENT_API_URL`은 이 컨테이너의 `MULINO_API_URL`로 매핑되며, 나머지 전달 환경변수는 필요한 HOME/CODEX_HOME 설정뿐입니다. Auth0 client secret·M2M access token·worker lease token은 전달하지 않습니다. Docker client 종료만으로 컨테이너 종료를 가정하지 않고, 취소 시 `docker rm --force`도 별도로 호출합니다. Docker 데몬 접근 장애까지 포함한 실제 컨테이너 종료 검증은 이미지 준비 후 필요합니다.

## 제어 프로토콜

기본값은 poll 5초, heartbeat 15초, lease 60초, 실행 상한 600초, 동시 실행 1개입니다. 실행기는 정확한 `runRef`, `workerId`, `leaseToken`을 heartbeat와 finish에 전달합니다. 일시적인 heartbeat 실패는 현재 lease 안에서만 재시도하고, lease 만료나 409/401/403에서는 실행을 종료합니다. 같은 논리 heartbeat·finish 재시도는 같은 `Idempotency-Key`와 요청 내용을 사용합니다.

**claim은 credential 발급 예외입니다.** 백엔드는 token hash만 저장하므로 응답 유실 후 원래 토큰을 재생할 수 없습니다. 실행기는 모호한 네트워크 실패·5xx·409 뒤 즉시 재claim하지 않고 60초 cooldown 후 새 요청을 보냅니다. 기존 Run은 백엔드의 lease 만료·복구 정책을 따릅니다. 204만 대기 업무 없음으로 해석합니다.

이미 agent CLI가 업무와 Run을 원자적으로 완료했으면 heartbeat/finish의 원래 terminal receipt가 우선합니다. 이후 모델 실패를 FAILED로 덮어쓰지 않습니다. 최종 모델 결과는 허용한 outcome·summary·waitingConditions·resultRef만 받습니다. WAITING에는 `DEPENDENCY_DONE` 또는 `SCHEDULED_TIME` 대기와 사유가 1~16개 필요하며, DONE에는 대기가 없어야 합니다. `SCHEDULED_TIME.payload.dueAt`은 실제 달력 날짜와 명시적 UTC/offset을 가진 RFC3339 시각이어야 합니다(예: `2026-09-06T09:00:00+09:00`). 날짜만 있는 값·존재하지 않는 날짜·별칭 필드는 거부하고 Java Instant와 맞추어 소수 초 9자리, offset ±18:00까지 지원합니다. DONE의 업무 근거는 서버가 다시 검증합니다.

백엔드가 `409 COMPLETION_NOT_VERIFIED` 또는 `400 INVALID_RESULT`를 반환하면 실행기는 heartbeat로 lease를 재확인합니다. 아직 RUNNING이면 새 논리 요청 키로 FAILED 종료를 한 번 시도하여 서버가 실패와 Attention을 기록할 수 있게 합니다. 이 실패 종료의 네트워크 재시도는 같은 새 키·본문을 유지합니다. 반복된 검증 거부에는 다시 실패 종료를 생성하지 않고 `FINISH_REJECTED`로 끝냅니다. 재확인 중 terminal receipt를 받으면 그 결과를 유지하며, `STALE_LEASE`나 권한 상실이면 추가 finish를 보내지 않습니다. 오류 본문의 `.error`에서 허용한 코드만 사용하고 자유 형식의 `.message`나 다른 별칭은 해석하거나 로그에 노출하지 않습니다.

출력은 전체 1 MiB, JSONL 한 줄 64 KiB, 맥락은 256 KiB로 제한합니다. ERP 소수·지수·큰 정수는 원문 문자열로 보존합니다. stdout/stderr 원문을 로그에 출력하지 않으며 최종 결과에는 알려진 자격증명을 가립니다. 로그에는 실행 시작·종료와 오류 유형만 남습니다. Auth0 토큰은 메모리에만 최대 1시간 캐시하고 실제 만료 전에 갱신합니다.

## 테스트와 모듈 API

- `Auth0TokenClient.getToken({signal, forceRefresh})`: M2M 발급·만료 전 갱신·동시 요청 병합.
- `WorkerApi.post(action, body, {idempotencyKey, signal})`: 제한된 내부 API 호출, 401일 때 한 번 토큰 갱신, 응답 크기 제한.
- `Runner.runOnce()`, `Runner.loop({signal})`, `Runner.stop()`: 단일 작업 처리·poll·종료. `clock.now/sleep` 주입으로 실제 분 단위 대기 없이 lease 경계를 테스트합니다.
- `DockerExecutor.start(claim, {secrets})`: `{result, cancel, pid}` 반환. `ProcessExecutor`의 command builder는 테스트에서 실제 Node fixture를 실행하기 위한 주입 경계입니다.

테스트는 idle·중복 claim 방지·확정 종료·heartbeat 갱신·토큰 갱신·실행 상한·취소·잘못된 JSON·과대 출력·lease 충돌·네트워크 만료·완료 receipt 보존·환경 격리·자격증명 가림을 검사합니다. 대기 16/17개 경계·엄격한 시각 형식·안전한 오류 코드·완료 근거 부족의 FAILED 전환·복구 중 lease 상실 및 완료 경쟁도 포함합니다.
