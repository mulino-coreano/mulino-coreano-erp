# Mulino Coreano — 인터페이스 메커니즘 (Interface Mechanism)

> 본 문서는 "챗봇이 붙은 ERP"가 아니라, **인간과 AI 에이전트가 동일한 Case·Work Item·증거·결정·ERP 상태 위에서 여러 표면(채널)으로 상호작용하는 지속성 있는 비즈니스 조직**을 정의한다.
> 스키마 구현: `database/ddl/07_case_management.sql` ~ `09_case_fks.sql` (Flyway V8~V11)
> 업무 흐름과의 관계: `docs/02_flow.md` (SSOT), 스키마 상세: `docs/03_erd.md`

---

## 1. 핵심 인간 모델: ASK / ACT / MONITOR

사용자는 에이전트 런타임 개념을 몰라도 시스템을 사용할 수 있어야 한다.

| 모드 | 의미 | 예 | 결과 |
|---|---|---|---|
| **ASK** | 비즈니스에 대해 질문 | "Amaretti 재고가 얼마나 있어?" | Query. **단순 질의는 자동으로 업무가 되지 않는다** |
| **ACT** | 조직에 목표를 위임 | "품절이 나지 않게 해줘" | Case 생성 → 에이전트 배정 → Work Item 동적 생성 |
| **MONITOR** | 물어보기 전에 알아야 할 것 관찰 | "지금 내 주의가 필요한 것" | 대시보드가 담당 |

ACT에서 인간은 **원하는 결과(outcome)** 를 말한다. 어떤 ERP 트랜잭션을 수행할지가 아니다.

---

## 2. 대화는 인터페이스이지 업무 단위가 아니다

ChatGPT 대화, Slack 스레드, 이메일 스레드는 사라질 수 있지만 비즈니스 의무는 계속된다.

```
Conversation
     │
     ▼
    Case        (≠ Conversation = Case)
```

월요일 ChatGPT에서 "Amaretti 품절이 나지 않게 해줘" → `CASE-1842` 생성.
수요일 Slack에서 "Amaretti 어떻게 됐어?" → 동일 Case 해소.
금요일 대시보드에서 `CASE-1842` 공급사 대기 중 표시.
**세 표면 모두 동일한 조직 상태를 투영한다.**

채널은 어댑터이다. "Slack 구매 에이전트", "이메일 구매 에이전트" 같은 것은 없다. 오직 하나의 Procurement Agent, 오직 하나의 `CASE-1842`.

---

## 3. 채널별 역할

| 채널 | 역할 | 특성 |
|---|---|---|
| **ChatGPT / Claude** | 주된 사고(thinking) 인터페이스 | 임의 ERP 질의, 리포트 생성, 목표 발행, 증거 검토, "왜?" 질문, 대안 비교, 승인 |
| **Slack** | 인간 주의(attention) 인터페이스 | 결정 요구, 예외, 짧은 상태 요청. **결과(consequence)를 노출하고 기계장치(machinery)는 노출하지 않는다** |
| **Email** | 비대칭 외부 경계 | 인바운드는 에이전트가 자율 소비. 아웃바운드는 에이전트가 초안까지 준비하고 **인간이 Send를 누른다** |
| **Dashboard** | 상시(ambient) 통제면 | 물어보기 전에 주의할 가치가 있는 것을 보여준다 |

Slack 안티패턴 — 다음은 내부 동작이므로 노출하지 않는다: "에이전트가 재고를 조회했다", "Work Item을 생성했다", "도구 X를 호출했다". 대신: "AMR-200 재보충 승인 필요 / 미조치 시 10월 11일 품절 예상 / 제안 +600케이스 / 증분 약정 ₩8.4M / [승인] [검토] [반려]".

### 이메일 비대칭

- 인바운드: 공급사 이메일 → 발신자 식별 → supplier/PO/shipment/Case 해소 → 첨부 추출 → Evidence/Observation/Claim → 관련 작업 재개. 인간이 도착 사실을 수동으로 알릴 필요가 없다.
- 아웃바운드: 에이전트는 수신자/참조/제목/본문/첨부를 준비하고 실제 초안을 생성하지만 **전송은 인간이 한다**. 인간은 외부 대표 권한을 제공한다.
- Send 이후 `EMAIL_SENT` 이벤트 → Case가 자동 재개된다. 에이전트는 Case 소유권을 인간에게 넘기지 않는다.

> **승인과 Send는 권한을 이전할 뿐, 소유권을 이전하지 않는다.**

---

## 4. Case — 채널 간 조정 객체

`cases` 테이블이 영속적 비즈니스 목표를 표현한다. 모든 채널은 `origin_channel_id`로 기록되지만 Case의 상태는 채널이 소유하지 않는다.

```
CASE-1842
  Goal: 10월 이전 Amaretti 품절 방지
  Assignees: Supply Chain / Procurement / Logistics Agent
  Human participants: 운영 매니저
```

### 다중 배정과 명확한 책임의 공존

Case에는 여러 행위자가 참여할 수 있지만(`case_participants`), 구체적 의무는 `work_items`에 산다.

```
WI-101 부족 노출 수량 산정       → Supply Chain Agent
WI-102 공급사 가용 수량 확인     → Procurement Agent
WI-103 긴급 운송 평가            → Logistics Agent
```

> 여러 행위자가 Case에 참여할 수 있지만, 각 미해결 의무에는 명시적 책임이나 대기 조건이 있다.

`ck_wi_single_assignee` 제약으로 하나의 Work Item은 에이전트 또는 사용자, 둘 중 하나에만 배정된다.

---

## 5. 공유 상태를 통한 조율 — 거대한 그룹 채팅이 아니다

에이전트는 주로 **내구성 있는 비즈니스 상태**를 통해 소통한다.

```
Procurement ──→ Claim / Evidence / Work Item ──→ Shared Case ──→ Logistics
```

`claim_evidence` 연결 테이블은 추론(Claim)과 결정론적 증거(Evidence)를 분리한다. Claim은 `ASSERTED / VERIFIED / CONFLICTED / REFUTED` 상태를 갖고, 반증률(refutation rate)은 대시보드의 에이전트 시스템 건강 지표가 된다.

직접 대화는 유용한 곳에서 허용되지만, 실질적 결과는 반드시 내구 상태로 승격된다. 회의와 대화는 사라져도, **결정과 의무는 살아남아야 한다.**

---

## 6. 논리 에이전트는 지속하고, 실행(Run)은 일회용이다

```
Procurement Agent (agents 테이블 — 조직 정체성)
   ├── RUN-9181
   ├── RUN-9182   (runs 테이블 — 일회용 실행)
   └── ...
```

사용자 경험: "구매가 여전히 이걸 처리 중이다". 실제로 모델 실행이 다섯 번 일어났더라도 그렇다. 사용자에게 런타임 진단(모델 실행 ID, 리스 타임아웃, 토큰 수, 재시도 횟수)은 노출되지 않으며, 엔지니어링/관리 인터페이스로 격리된다.

---

## 7. 컨텍스트 6계층과 참조 방식

실행 전 Context Builder가 Case Context를 재구성해 전달한다. 거대한 대화 리플레이가 아니다.

| 계층 | 내용 | 주요 소스 |
|---|---|---|
| Objective | 무엇을 달성하려 하는가, 성공 조건 | `cases.objective` |
| Obligation | 지금 내가 책임진 것 | `work_items` |
| Organizational | 누가 함께 일하고 무엇을 하는가 | `case_participants` |
| Business | 관련 ERP 엔터티·거래·이력 | L0 코어 테이블 |
| Epistemic | 아는 것 / 추론한 것 / 충돌 / 미지 | `evidence`, `claims` |
| Control | 능력·정책·경계 | 거버넌스 승인 매트릭스 |

컨텍스트 패키지는 **기업 지식의 색인**이지 거대한 프롬프트가 아니다. 요약 사실 + `EV-91` 같은 증거 참조를 내려주고, 에이전트가 필요할 때 깊은 컨텍스트를 추가 조회한다. Run의 `context_snapshot` JSONB에 이 색인의 스냅샷을 남긴다.

---

## 8. 대기는 인터페이스 메커니즘의 일부다

```
에이전트: "지금 할 수 있는 것은 다 했다. 공급사 회신을 기다린다."
     ↓
WI-102 status = WAITING, WAIT-83 (condition = supplier reply)
     ↓
(2일 후) 공급사 회신 도착 → 대기 조건 충족 → WI = READY → 새 Procurement 실행
```

대기 중 LLM은 살아있지 않다. 사용자 관점에서 Procurement는 문제를 계속 소유한 것이다. `resolved_by_event_id`가 대기를 깨운 이벤트를 가리켜 재개 근거를 감사 가능하게 한다.

이벤트가 모든 인터페이스를 연결한다: `CHANGE_REQUEST_APPROVED`(Slack 승인), `EMAIL_SENT`(인간 Send), `SUPPLIER_EMAIL_RECEIVED`, `THIRD_PARTY_STOCK_REPORT_RECEIVED`(3PL 워크북), `INVENTORY_CHANGED`(ERP 상태 변경). 이벤트는 Case를 갱신하고 대기 조건을 충족시킨다.

그 뒤 디스패처가 실행할 에이전트를 결정한다 — 배정이 연속 실행을 뜻하지 않는다. 공급사 회신이 오면 Procurement WI만 READY가 되고, Logistics/QC WI는 영향받지 않아 **해당 에이전트만 실행된다.**

---

## 9. 인간 주의 자체가 인터페이스 자원이다

시스템은 아래 다섯 가지 구체적 사유(`attention_reason_type`)일 때만 인간을 중단한다.

| 사유 | 의미 |
|---|---|
| `AUTHORITY_REQUIRED` | 에이전트의 위임 권한 초과 (예: 발주 금액 초과) |
| `JUDGMENT_REQUIRED` | 둘 다 유효한 선택지 중 정책이 없어 판단 필요 |
| `MISSING_HUMAN_CONTEXT` | 인간만 아는 맥락 부족 |
| `EXTERNAL_SEND_REQUIRED` | 외부 대표 권한 필요 (이메일 Send) |
| `MATERIAL_EXCEPTION` | 중대 예외 |

모든 요청은 "왜 당신이 필요한가"를 설명해야 한다(`question` + `consequence`).
질문은 **가장 작은 유용한 질문**이어야 한다: "이 Case를 어떻게 처리할까요?" (X) → "고객 A의 런칭 약정이 고객 B보다 계약상 우선합니까?" (O).

인간의 답변에는 범위(`answer_scope`)가 필요하다: 이 액션만 / 이 Case / 이 캠페인 / 이 고객 / 일반 정책. 일회성 답변이 자동으로 보편 정책이 되어서는 안 된다.

---

## 10. 정정과 설명의 횡단 원칙

- **정정은 대화 경계를 넘는다**: 인간이 "공급사 MOQ가 지난달 800으로 바뀌었어"라고 말하면, 어시스턴트 대화 기억이 아니라 Observation/선언 → 증거 탐색 → 표준 상태 고려 → 영향받은 Claim/Case 갱신으로 이어진다.
- **설명은 출처(provenance)를 노출한다**: "현재 ETA 9월 18일 — 1차 출처: 포워더 이메일 EV-122 (9/2 14:31), 보강 출처: 선사 스케줄 EV-126, 이전 ETA: 9월 21일, 영향 Case: CASE-1842, CASE-1901". 이것은 감사 전문 워크플로가 아니라 일반 사용자 상호작용이다.
- **리포트는 대부분 생성된다**: "60일 유통기한 위험 리포트를 줘"처럼 현재 비즈니스 상태에서 즉석 생성한다. 리포트는 휘발되어도 ERP 사실·Case·증거·결정이 표준으로 남는다.

---

## 11. 쿼리와 업무의 경계

```
"재고가 얼마야?"        → QUERY → 답변
"재고가 너무 많아. 고쳐줘." → WORK → Case → Agents
```

쿼리는 능력(capability)을 사용하고, 목표(objective)는 책임(responsibility)을 만든다. ASK/MONITOR는 Case를 생성하지 않는다(설계 규칙이며 DB 제약이 아닌 애플리케이션 규칙으로 강제한다).

---

## 12. 전체 루프

```
                    HUMAN
             ┌────────┼────────┐
            ASK      ACT    MONITOR
             │        │        │
      ChatGPT/Claude  │    Dashboard
             └────┬───┘
                  ▼
                CASES
        ┌─────────┼─────────┐
      Agents   Work Items  Evidence
        │                      
   Dispatcher → Agent Execution (Claude / Codex)
        │
    capabilities → ERP → Events
        │
        ├─► Cases resume  ├─► Slack attention  ├─► Dashboard  └─► Email workflow
```

### 압축된 인터페이스 철학

1. 영속적 업무 표면은 대화가 아니라 Case다.
2. 사용자는 ERP 트랜잭션 순서가 아니라 질문과 목표를 표현한다.
3. ChatGPT/Claude가 주된 추론 인터페이스다.
4. Slack은 인간 주의 인터페이스다.
5. 이메일은 자율 인바운드, 인간 게이트 아웃바운드 채널이다.
6. 대시보드는 상시 운영 인지를 제공한다.
7. 모든 채널은 동일한 Case와 비즈니스 상태를 투영한다.
8. 에이전트는 영속 채팅이 아니라 내구성 Case 객체로 조율한다.
9. 논리 에이전트는 지속하고 LLM 실행은 일회용이다.
10. 이벤트와 대기 조건이 시간을 가로지르는 연속성을 제공한다.
11. Context Builder가 실행마다 최소 관련 비즈니스 컨텍스트를 재구성한다.
12. 인간 주의는 환원 불가능한 권한·판단·맥락·외부 대표에만 요청한다.
13. 대화의 실질적 결론은 구조화된 조직 상태가 된다.
14. 인터페이스는 에이전트 런타임 기계장치가 아니라 비즈니스 결과와 결과(consequence)를 보여준다.
