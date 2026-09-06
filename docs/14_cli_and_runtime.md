# 역할 CLI와 Codex 실행 이미지

현재 Zig CLI는 인증된 Case·계획·자재·발주 조회, 공급망 계획 계산, 구매 제안, Orchestrator의 후속 업무 생성과 현재 업무 전이를 제공한다. Docker 이미지에는 이 Linux 실행 파일과 Codex 0.151.0, 역할 지침, 결과 JSON 스키마를 넣었다. **인간 대화 도구와 서버 관리 후속 책임을 연결했으며 실제 로그인·모델 실행 인수는 아직 남아 있다.**

## 구현 범위

| 구성 | 현재 동작 |
|---|---|
| `agents/cli/` | Zig 0.16.0 표준 라이브러리만 사용. `case show`, `plan calculate/show`, `work create/transition`, `material show`, `po propose/show`의 8개 경로 |
| 공급망 지침 | 인간이 확정한 거점·품목·목표일 유지 → 서버 계산 → 저장 계획 조회 → 근거를 가진 완료 제안 |
| Orchestrator 지침 | 고정 요청 키로 공급망·구매 업무 참조 복구 → 실제 상태 확인 → 의존 대기 후 종료·재개 |
| Procurement 지침 | 정확한 계획으로 제안 → 서버 executionResult 반환 후 종료 → 인간 결정 후 실제 PO 조회·완료 검증 |
| 후속 책임 | 서버가 입고를 관찰하는 ORCHESTRATOR 업무. 일반 Run으로 claim하지 않고 Case WAITING 유지 |
| 런타임 | 호스트 Node가 lease·프로세스를 관리하고, 컨테이너는 Run capability로 CLI를 호출 |
| 후속 | 실제 native subagent 업무 수행과 두 인간 클라이언트의 전체 시연 |

CLI의 구체적 인수·본문은 [CLI 안내](../agents/cli/README.md)를 따른다. 명령은 ERP SQL이나 업무 계산을 포함하지 않는다. JSON 응답 숫자를 재직렬화하지 않아 소수·큰 정수의 원문을 보존한다. 변경은 호출자가 지정한 요청 키를 전달하며 자동 재시도·HTTP 리다이렉트는 하지 않는다. TLS 검증과 제한 시간·응답 크기 제한을 적용한다. API 오류는 상태 코드와 제한된 JSON 오류만 반환한다.

## 이미지와 실행 준비

저장소 루트에서 다음을 실행한다. 빌드에는 Zig 0.16.0과 실행 중인 Docker가 필요하다.

```bash
node agents/runner/scripts/build-image.mjs
node agents/runner/scripts/smoke-image.mjs
```

빌드 스크립트는 Docker 호스트 아키텍처에 맞는 정적 Linux 바이너리를 만들고, 임시 빌드 디렉터리에 허용한 파일만 복사한다. 저장소 전체·환경 파일·사용자 홈은 이미지에 전달하지 않는다. Node 기반 이미지 digest와 Codex 패키지 버전·무결성 lock을 고정하며 HTTPS 검증용 CA 인증서를 포함한다.

실제 실행에는 [Auth0 연결 안내](11_auth0_setup.md)의 worker M2M 설정, 가동 중인 백엔드, 전용 Codex 로그인 볼륨과 사용 가능한 모델 이름이 필요하다. 로그인은 사용자 계정의 별도 준비 단계다. 호스트의 기존 로그인 파일을 복사하지 않는다.

```bash
docker volume create mulino-codex-auth
docker run --rm -it --user=10001:10001 \
  --mount type=volume,src=mulino-codex-auth,dst=/home/mulino/.codex \
  mulino-codex-runtime:0.151.0 login --device-auth
```

위 로그인 명령은 준비 방법이며 이번 검증에서 실행하지 않았다. 실행기의 `.env.example`을 바탕으로 gitignored `.env`에 환경별 값을 설정한다. `MULINO_CODEX_MODEL`은 명시적으로 지정해야 실행기를 시작할 수 있다. `MULINO_RUNTIME_IMAGE`에는 방금 만든 이미지, `MULINO_CODEX_AUTH_VOLUME`에는 전용 볼륨을 지정한다.

```bash
cd agents/runner
node --env-file=.env src/main.js
```

## 실행 권한과 데이터

Docker의 읽기 전용 root filesystem, UID/GID 10001, 임시 `/work`·`/tmp`, Linux capability 제거, `no-new-privileges`, 자원 제한이 실행 경계다. 사용자 홈·저장소·Docker socket을 mount하지 않는다. 실제 컨테이너에서 중첩 bwrap이 namespace 권한 부족으로 실행되지 않아 Codex는 `--sandbox=danger-full-access`와 `approval_policy="never"`로 컨테이너 안에서 실행한다. 이 설정을 호스트에서 직접 쓰는 실행 방식은 제공하지 않는다. Docker를 privileged로 바꾸거나 호스트 kernel 설정을 완화하지 않았다.

인간 Auth0 토큰, worker M2M 시크릿, lease token은 컨테이너에 전달하지 않는다. CLI에는 현재 Run의 capability와 API 주소를 환경변수로 전달한다. Codex shell 환경도 필요한 이름만 허용한다. 사용자 설정·규칙·자동 AGENTS 문서는 읽지 않고, 역할 allowlist로 선택한 고정 지침과 결과 스키마를 사용한다. Codex 설정 방식은 [공식 설정 문서](https://learn.chatgpt.com/docs/config-file/config-reference)를 따른다.

native subagent는 현재 Run 안에서 분석을 나눌 수 있다. 다른 ERP 역할의 쓰기는 해당 역할에 배정된 Work Item/Run과 capability가 필요하다. 런타임의 하위 세션 이름만으로 서버 권한이 바뀌지 않는다. 실제 native dispatch 시연은 아직 수행하지 않았다.

Worker API 응답의 소수·지수·JavaScript 안전 범위 밖 정수는 원문 숫자 문자열로 보존한다. 서버의 NUMERIC 값을 Node Number로 반올림해 모델에 넘기지 않는다. 예시 데이터의 저장 계획과 최신 ERP 사실을 포함한 실제 claim 맥락은 이 변환 후 216,428바이트로 현재 262,144바이트 한도 안에 있다. 더 큰 업무 데이터의 수용을 검증한 값은 아니며, 과거 source snapshot과 최신 사실을 함께 담는 현재 구조는 이후 참조·필요 부분 조회로 줄일 여지가 있다.

## 검증 범위

- Zig 단위 테스트 6개, 독립 HTTP smoke 32개 통과. 구매 제안의 빈 본문·요청 키, 승인 대기/구매 불필요 응답과 추가 요청 없는 종료도 포함한다. 잘못된 TLS 인증서, 리다이렉트·자동 재시도 차단, 토큰 반사, 소수 정밀도, 응답 크기, 헤더/부분 본문 제한 시간을 포함한다.
- 부분 본문 제한 시간 시험에서 Zig 0.16.0 `Client.fetch` 취소 경로의 비정상 종료를 재현했다. 하위 request/response API로 교체한 뒤 정상 JSON 오류·exit 2를 확인했다.
- ARM64와 AMD64 Linux 정적 바이너리를 빌드했다. 실제 Docker 실행 검증은 ARM64에서 수행했다.
- 이미지 smoke는 가짜 capability와 별도 임시 볼륨으로 사용자·파일·권한·환경·CA·CLI·정확한 JSON·Codex 설정 파싱과 취소 시 컨테이너 삭제를 확인한다. 외부 네트워크는 차단하며 모델 요청은 0건이다.
- Node 실행기 자동 테스트와 최신 Backend 검증은 [실행·계획 API 안내](13_execution_and_plan_api.md)를 따른다. 실제 Auth0/ChatGPT/Codex 로그인·갱신, 실제 모델 업무 수행과 발주 승인 인수를 이 결과로 대체하지 않는다.
