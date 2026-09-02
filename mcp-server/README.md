# Mulino Coreano ERP — MCP Server

ChatGPT, Claude Desktop 등 MCP 클라이언트가 **하나의 비즈니스 표면**(Case)을 공유하도록 노출하는 커넥터입니다.

## 실행

```bash
npm install
npm start   # Mulino Coreano backend (localhost:8080) 기본 상대
```

## 제공 도구

| tool | 설명 |
|---|---|
| `ask_inventory` | ASK — 읽기 전용 ERP 질의 (Case 생성 안 함) |
| `create_case` | ACT — 비즈니스 목표 위임으로 영속 Case 생성 |
| `list_cases` / `monitor_status` | MONITOR — 현황 조회 |
| `list_attention` | 인간 주의 필요 항목 (권한·판단 컷오프) |

## 설계 결정 (docs/08_interface_overview.md §3)

- 대화는 인터페이스이고, Case가 업무의 영속 표면입니다.
- QUERY는 자동으로 업무가 되지 않습니다 — 사용자가 명시할 때만 ACT로 전환합니다.
- 외부 대표 권한(이메일 발송 등)은 인간에게 있으며, 이 서버에는 포함하지 않았습니다.
