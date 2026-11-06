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

## 로컬 인간 역할과 결정 (#47·#48·#52)

백엔드는 `SPRING_PROFILES_ACTIVE=local`로 기동한다. 인간 도구는
`MULINO_LOCAL_ROLE` (기본 OPERATOR)을 `X-Mulino-Local-Role`로 보낸다.
service/capability/Authorization 헤더와 internal/agent API는 쓰지 않는다.
역할 헤더는 로컬 PoC 전용이며 외부 인증을 대체하지 않는다.

| 도구 | 입력과 행동 |
|---|---|
| `whoami` | 현재 인간 역할 조회 |
| `get_case` / `get_plan` | caseRef / planRef로 업무·계획 조회 |
| `get_approval` / `get_purchase_order` | approvalId / purchaseOrderId로 승인 근거·실제 발주 조회 |
| `decide_purchase` | MANAGER의 APPROVE/BLOCK. approvalId, expectedVersion, proposalHash, reason 필수 |
| `answer_attention` | OPERATOR·MANAGER의 일반 답변. attentionRequestId, expectedVersion, answer, scope 필수 |

쓰기 도구의 `requestKey`는 선택적이다. 생략하면 UUID를 생성해 오류에도
반환한다. 같은 입력의 사용자 지시 재전송만 같은 key를 쓴다. 자동으로
재시도하지 않는다. 구매 승인 Attention은 get_approval 확인 뒤
인간의 명시적 선택으로 decide_purchase를 호출한다. 일반 답변은 ERP
승인을 대신하지 않는다. Node 22 이상을 사용한다. decimal과 unsafe ID는
응답 문자열로 보존한다. 전체 계약은
[인간 답변·구매 결정](../docs/14_human_purchase_api.md)에 있다.

## 로컬 업무 SIT

`npm test`는 stdio 요청·권한 헤더·정확한 숫자 전달을 검증한다.
`node scripts/local-human-flow.mjs`는 이미 준비된 local 구매 제안을
실제 HTTP API로 승인하고 같은 발주 묶음과 답변을 재조회한다.
`MULINO_API_BASE`, `MULINO_TEST_APPROVAL_ID`, `MULINO_TEST_PLAN_REF`,
`MULINO_TEST_CASE_REF`, `MULINO_TEST_ATTENTION_ID`를 지정해야 한다.
이 script는 실제 ERP 발주를 생성하므로 폐기용 fixture DB에서만 실행한다.
worker secret·capability를 받지 않으며 모델 실행·UAT를 뜻하지 않는다.
