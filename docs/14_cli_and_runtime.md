# 역할 CLI와 실행기

저장된 Case와 Work Item을 모델 프로세스가 이어서 수행하려면
Run lease와 업무 capability를 분리해야 한다. 호스트 runner는 내부
lease API를 호출하고 컨테이너의 `mulino`는 허용된 업무 API만 호출한다.
DB 접속과 인간 승인·답변 명령은 agent CLI에 없다.

## 배포 설정과 전환

백엔드의 `MULINO_AGENT_RUNTIME`은 `CODEX` 또는 `CLAUDE`다.
기본값은 `CODEX`이며 다른 값이면 시작을 거부한다.
`agent.runtime.default`에 매핑되고 초기 보충 접수, 역할 배정,
Dispatcher 재개, 인간 답변 재개, lease 재시도에 적용된다.

runner는 같은 `MULINO_AGENT_RUNTIME`과 명시적
`MULINO_AGENT_MODEL`, `MULINO_RUNTIME_IMAGE`,
`MULINO_AUTH_VOLUME`, `MULINO_WORKER_ID`를 설정한다.
claim 본문에 runtime을 선언하며 서버는 해당 runtime의 QUEUED Run만
준다. 기존 큐의 runtime을 전환 작업이 수정하지 않는다.
기존 worker를 종료하고 백엔드와 새 worker의 설정을 바꿔 재시작한다.
이전 runtime의 미처리 큐는 해당 runtime worker로 마무리한다.

호스트의 `MULINO_LOCAL_SERVICE_SECRET`은 local profile 백엔드와
같은 값이다. 내부 요청에는 `X-Mulino-Local-Service`만 전송한다.
역할 헤더와 Bearer capability를 내부 요청에 섞지 않는다.
컨테이너에는 이 secret과 lease token을 전달하지 않는다.
컨테이너 CLI는 `MULINO_TOKEN`의 Run capability를
`Authorization: Bearer`로 전달한다.

## 명시적 인간 보충 접수

기존 objective/intentType/channel 요청은 초기 READY 업무만 만든다.
익명 접수는 opener가 NULL이고 Idempotency-Key를 무시하며 Run을
만들지 않는다. 로컬 MANAGER/OPERATOR가 선택적으로 아래 범위를
전달할 때만 초기 Orchestrator Run을 함께 예약한다.

```json
{"objective":"지정 품목 보충","channel":"CHAT","replenishment":{"productSkus":["DEMO-AMR"],"warehouseId":1,"targetDate":"2026-09-05"}}
```

warehouse는 planning policy가 있어야 하고 SKU는 활성 완제품이어야
한다. targetDate는 planningClock 기준 1~90일 범위다.
생략하면 warehouse policy의 horizon을 쓴다. 서버가 확정한
productIds/warehouseId/targetDate를 Case metadata에 저장한다.
익명 요청에는 이 기능을 허용하지 않는다. MCP `create_case`도
선택 replenishment와 requestKey를 전달하며 인간 역할 헤더만 쓴다.

## 프로세스와 이미지 검증

CLI 빌드·명령 계약은 [CLI README](../agents/cli/README.md),
실행기 설정·상태 처리는 [runner README](../agents/runner/README.md)를
따른다. Node 24.16.0, Zig 0.16.0, Codex CLI 0.154.0,
Claude Code 2.1.282를 고정한다.

```bash
node agents/runner/scripts/build-image.mjs mulino-agent-runtime:stack-53-24
node agents/runner/scripts/smoke-image.mjs mulino-agent-runtime:stack-53-24
```

빌드는 allowlist로 만든 임시 context만 Docker에 전달한다.
smoke는 network none과 임시 volume으로 기동·CLI·버전·읽기 전용
filesystem·일반 사용자·취소 후 컨테이너 제거를 확인한다.
기존 로그인 volume을 읽거나 수정하지 않고 모델 요청은 0건이다.

Codex와 Claude는 같은 역할 지침과 결과 schema를 사용한다.
runner는 자식 하나만 감독하고 heartbeat, lease 만료, 실행 상한,
취소, 출력 크기를 제한한다. 원문 stdout/stderr는 로그에 남기지 않고
최종 결과에서 capability·lease·호스트 secret을 가린다.
`model_finished`에는 runtime/model과 실제 제공된 usage만 기록한다.
Codex의 token 사용량에서 비용을 추정하지 않는다. Claude가 반환한
costUsd는 그대로 기록한다.

이 검증은 source 역할 프로세스와 이미지 계약을 확인한다.
실제 모델의 보충 업무 성공과 native subagent 수행은 증명하지 않는다.
#24의 실모델 인수는 #57 fixed-clock scenario harness에서 이어간다.

## 코드·이미지 확인 범위

2026-10-03 코드 검증은 backend 473건, MCP 14건, runner 53건,
CLI HTTP smoke 32건과 Zig test/build를 통과했다. Docker
network-none smoke의 modelRequests는 0건이다. 실제 MCP stdio와
HTTP·폐기 가능한 DB를 연결해 보충 범위 저장, CLAUDE 초기 Run
1건, 같은 키 replay, 일반·익명 접수 Run 0건을 확인했다.
실모델 호출과 native subagent 검증은 실행하지 않았다.
