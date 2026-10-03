# Mulino Coreano ERP — MCP Server

로컬 stdio MCP 클라이언트(Claude Desktop 등)가 **하나의 비즈니스 표면**(Case)을 공유하도록 노출하는 커넥터입니다. 원격 ChatGPT 연결에는 별도의 HTTP 전송 계층이 필요하며 현재 범위에는 포함되지 않습니다.

## 실행

```bash
npm install
npm start   # Mulino Coreano backend (localhost:8080) 기본 상대
```

`MULINO_API_BASE`로 백엔드 주소를, `MULINO_API_TIMEOUT_MS`로 호출 제한 시간을 설정할 수 있습니다. 제한 시간 기본값은 10초입니다. 변경 요청의 응답이 시간 초과되면 서버 반영 여부가 불확실하므로 커넥터는 자동 재시도하지 않습니다.

## 제공 도구

| tool | 설명 |
|---|---|
| `ask_inventory` | ASK — 제품명/SKU 완제품 재고 검색 또는 명시적인 전체 재고 조회 (Case 생성 안 함) |
| `create_case` | ACT — 비즈니스 목표 위임으로 영속 Case 생성 |
| `list_cases` / `monitor_status` | MONITOR — 현황 조회 |
| `list_attention` | 인간 주의 필요 항목 (권한·판단 컷오프) |

## 설계 결정 (docs/08_interface_overview.md §3)

- 대화는 인터페이스이고, Case가 업무의 영속 표면입니다.
- QUERY는 자동으로 업무가 되지 않습니다 — 사용자가 명시할 때만 ACT로 전환합니다.
- 외부 대표 권한(이메일 발송 등)은 인간에게 있으며, 이 서버에는 포함하지 않았습니다.

## 로컬 인간 역할 (#47)

`create_case`는 `MULINO_LOCAL_ROLE`을 `X-Mulino-Local-Role` 헤더로
보낸다. 기본값은 OPERATOR다. local 백엔드는 OPERATOR·MANAGER의
접수만 허용한다. 조회 도구에는 역할 헤더를 보내지 않는다.
백엔드는 `SPRING_PROFILES_ACTIVE=local`로 기동한다.
이 헤더는 로컬 PoC 전용이며 외부 인증을 대체하지 않는다.
MCP의 키 지정 재시도는 아직 제공하지 않는다. REST 호출에서만
선택적인 `Idempotency-Key`를 사용할 수 있다.

#48은 인간 조회 도구 `whoami`, `get_case(caseRef)`, `get_plan(planRef)`을
추가한다. 이 도구는 로컬 역할 헤더만 전달한다. `get_plan`은 local
프로필에서만 사용 가능하다. 후속 승인·답변 도구는 #52에 남는다.
