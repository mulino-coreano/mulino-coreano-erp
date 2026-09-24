# Mulino Coreano ERP — MCP Server

PoC 로컬 stdio MCP 커넥터입니다. `MULINO_LOCAL_ROLE` 환경 변수로 역할을 지정하고 `X-Mulino-Local-Role` 헤더로 backend에 전달합니다. 원격 Streamable HTTP transport와 Auth0 OBO token exchange는 #22에서 보류됩니다.

## 준비와 실행

Node.js 22 이상이 필요합니다. 의존성은 MCP SDK `1.30.0`으로 고정하며 `package-lock.json`을 함께 사용합니다.

```bash
cd mcp-server
npm ci
npm test
```

이 구현의 자동 시험은 로컬 ERP HTTP fixture를 사용합니다. 실제 외부 신원 제공자(#21·#22) 로그인·갱신과 ChatGPT/Codex 원격 MCP 연결은 보류됩니다.


### 로컬 stdio

```bash
# MULINO_LOCAL_ROLE과 MULINO_API_BASE를 환경에서 주입한 뒤:
npm start
```

`MULINO_LOCAL_ROLE`은 PoC 로컬 역할(`MANAGER`, `OPERATOR` 등)이며 필수입니다. 미설정이면 프로세스가 종료됩니다. 매 API 요청에 `X-Mulino-Local-Role` 헤더로 전달하며, 신원 검증은 backend가 수행합니다. 원격 HTTP transport와 Auth0/OBO 경로는 삭제됐습니다(#22 보류).

## 제공 도구

| tool | 설명 | Scope | 읽기 전용 |
|---|---|---|---|
| `whoami` | 인증된 사용자·ERP 역할·capability 확인 (`GET /me`) | `erp:read` | 예 |
| `ask_inventory` | ASK — 제품명/SKU 완제품 재고 검색, 명시적 전체 조회 | `erp:read` | 예 |
| `create_case` | ACT — 목표 접수·재보충 범위 전달, 활성 범위와 겹치면 기존 Case 연결 | `work:write` | 아니오 |
| `list_cases` | 상태별 Case 조회 | `erp:read` | 예 |
| `list_attention` | 인간의 권한·판단이 필요한 항목 조회 | `erp:read` | 예 |
| `monitor_status` | 저장된 운영 현황의 읽기 전용 snapshot | `erp:read` | 예 |

기존 조회·목표 접수 도구에 인간 신원 확인, Case·계획·승인안·발주 조회, 구매 결정과 일반 질문 답변을 추가했습니다. 질문은 자동으로 Case를 만들지 않습니다. 실행기 API는 인간 MCP에 노출하지 않으며 `monitor_status`는 backend의 재판정·Run dispatch를 실행하지 않습니다.

### 목표 접수와 재보충 범위

`create_case`는 필수 `objective`와 선택 `channel`, `requestKey`, `replenishment`를 받습니다. 새 Case를 접수하면 Orchestrator 업무 실행을 예약하며, 실제 LLM이 이미 실행 중이라고 안내하지 않습니다. 백엔드 응답의 `reused=true`이면 “기존 Case에 연결됨”, 새 접수이면 “Case 접수됨”으로 표시합니다. 기존 응답과 `metadata`, `reused`는 구조화 결과에 그대로 보존합니다.

```json
{
  "objective": "10월 이전 AMR-200 품절 방지",
  "requestKey": "amr-replenishment-20260905-1",
  "replenishment": {
    "productSkus": ["AMR-200"],
    "warehouseId": 1,
    "targetDate": "2026-10-01"
  }
}
```

`replenishment`를 전달할 때 `productSkus`는 1~100개이며 각 SKU는 비어 있지 않은 50자 이하 문자열이어야 합니다. 대화에 명시된 SKU만 추출하고 제품명에서 SKU를 만들어내지 않습니다. `warehouseId`와 `targetDate`는 선택 사항으로, 생략하면 백엔드가 계획 정책을 확인합니다. 창고 ID는 양의 정수이며 큰 ID는 정확한 숫자 문자열로 전달합니다. `targetDate`는 실제 달력 날짜인 `YYYY-MM-DD`만 허용합니다. 인간 신원과 ERP 역할은 인증에서 결정하므로 사용자 ID·역할 입력은 제공하지 않습니다.

`requestKey`는 해당 요청의 `Idempotency-Key` 헤더로 전달하며 본문에는 넣지 않습니다. 호출자가 지정한 1~200자의 ASCII 키를 그대로 보존하고, 생략하면 호출당 UUID를 한 번 생성합니다. 성공·API 오류의 `structuredContent.requestKey`에서 실제 사용한 키를 확인할 수 있습니다. 호출자가 같은 요청을 다시 보내기로 결정했을 때 **같은 키와 같은 입력**을 재사용합니다. 입력을 바꿔 같은 키를 쓰면 백엔드가 409로 거부합니다. 새 요청은 새 키를 사용합니다.

수량·가격의 `NUMERIC(18,6)` 정밀도를 보존하기 위해 ERP JSON의 소수/지수 표기 숫자와 JavaScript 안전 범위를 벗어나는 정수는 MCP 응답에서 원래 숫자 문자열로 반환합니다. 예를 들어 `999999999999.999999`를 `1000000000000`으로 반올림하지 않습니다. 작은 정수 건수와 boolean은 기존 타입을 유지합니다.

## 오류와 검증 범위

ERP 응답 본문·시크릿·내부 오류는 도구 오류에 노출하지 않습니다. 백엔드의 401/403 등 상태 번호는 반환하되 응답 본문은 공개하지 않습니다. Redirect는 따라가지 않습니다.

API 제한 시간이 지나면 호출을 자동 재시도하지 않습니다. 변경 요청은 서버에서 이미 반영되었을 가능성을 안내하며 상태를 먼저 확인해야 합니다. 이후 호출자가 `create_case` 재시도를 결정하면 오류 결과의 `requestKey`와 원래 입력을 그대로 사용합니다.

`npm test`는 실제 SDK stdio 클라이언트와 로컬 ERP endpoint로 다음을 검증합니다: backend 연결, 역할 헤더 전달, 오류 비밀 제거, 기존 도구 호환성과 변경 요청 timeout. 목표 접수는 요청 키의 생성·유지·오류 반환, 정확한 재보충 본문, 신규/기존 Case 문구, 잘못된 입력 거부, 큰 ID 정밀도와 자동 재시도 없음을 추가로 검증합니다.

## 인간 대화 도구

| 도구 | API | Scope |
|---|---|---|
| `get_case(caseRef)` | `GET /cases/{ref}/overview` | `erp:read` |
| `get_plan(planRef)` | `GET /plans/{ref}` | `erp:read` |
| `get_approval(approvalId)` | `GET /approvals/{id}` | `erp:read` |
| `get_purchase_order(purchaseOrderId)` | `GET /purchase-orders/{id}` | `erp:read` |
| `decide_purchase(approvalId, decision, expectedVersion, proposalHash, reason, requestKey?)` | `POST /approvals/{id}/decision` | `procurement:decide` |
| `answer_attention(attentionRequestId, answer, expectedVersion, scope, requestKey?)` | `POST /attention/{id}/answer` | `work:write` |

`list_cases`는 `q`(목표·제목의 리터럴 검색어, 최대 200자), `productSku`(정확한 SKU, 최대 50자), `status`를 함께 받습니다. `get_case`는 인간·에이전트 참여자, 활성 대기, 승인·근거와 남은 의무를 표시합니다. 요약은 backend 계산을 사용하고 전체 backend 응답은 `structuredContent`에 보존합니다. 조회는 작업 실행이나 변경을 일으키지 않습니다.

구매 결정은 `get_approval`에 표시된 공급사별 품목·정확한 수량·단가·합계와 **해당 버전·해시**를 검토한 인간이 **매번 명시적으로** 선택한 `APPROVE` 또는 `BLOCK`만 전달합니다. `APPROVE`는 실제 발주를 생성할 수 있으며 MANAGER 권한이 필요합니다. 저장된 정책이나 이전 동의는 자동 승인 권한이 아닙니다. 서버의 버전·해시·권한 검증은 클라이언트가 인간에게 확인받았다는 증명이 아닙니다. 발주 생성 후에도 생산·입고 이행 확인이 남습니다.

일반 질문의 답변은 최신 `version`을 `expectedVersion`으로 전달하고 `THIS_ACTION` 또는 `THIS_CASE` 범위를 지정합니다. `governanceActionId`가 있는 구매 승인 요청은 일반 답변 경로로 처리할 수 없습니다. `THIS_CASE`도 향후 구매 자동 승인 정책을 만들지 않습니다. 답변 기록·작업 재개 예약은 목표 완료를 의미하지 않습니다.

ID는 안전한 양의 정수 또는 signed 64-bit 범위의 정확한 숫자 문자열을 사용합니다. 큰 ID와 ERP 소수 금액은 숫자로 다시 변환하지 않습니다. 버전은 1~2147483647 정수, 제안 해시는 소문자 64자리 SHA-256, 사유는 비어 있지 않은 최대 4000자, 답변은 최대 8000자입니다. 사용자·역할·대리 실행 필드 등 미지원 입력은 거부합니다.

두 변경 도구는 `create_case`와 같은 요청 키 규칙을 사용합니다. 키를 생략하면 한 번 생성하여 성공·API 오류 모두 `structuredContent.requestKey`로 반환하며, 오류 본문과 자격증명은 노출하지 않습니다. 타임아웃 후 자동 재시도하지 말고 현재 상태를 조회한 뒤 사용자가 재시도를 지시한 경우에만 같은 키와 같은 입력을 사용합니다. `procurement:decide`를 Auth0 MCP/ERP API와 OBO 권한에 함께 설정해야 하며, 실제 tenant 로그인 검증은 별도입니다.
