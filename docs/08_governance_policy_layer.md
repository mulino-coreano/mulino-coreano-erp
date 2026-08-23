# Mulino Coreano — 거버넌스 정책 레이어 설계 (Governance Policy Layer)

> 상태: **설계 (Phase 3+)** — `docs/02_flow.md`의 거버넌스 승인 매트릭스와 에이전트 개입 요약(SSOT)을 확장한다. 이 문서는 스키마 변경이 아니라 **정책 분류 체계**를 정의하며, 실행 스크립트는 `scripts/validate_governance_policy.py`(본 PR 범위) 및 이후 L1 백엔드 구현(Phase 4+)에서 참조한다.
>
> 근거: 2026-08-23 딥리서치 (agent-guardrails / regulated-LLM / autonomy-literature 3개 스트림). 상세 소스와 등급은 `docs/09_governance_research.md` 참조.

---

## 1. 문제 정의 (이 문서가 왜 필요한가)

현재 승인 매트릭스는 **모든 쓰기를 사람 승인**으로 가정한다. 그러나 2026-08 대화에서 확인한 핵심 문제는:

> **"모든 결정을 인간에게 위임하면 승인자가 병목이 되고, 승인자가 컨텍스트 없이 결정만 떠맡으니 의사결정 품질까지 떨어진다."**

리서치가 이 문제를 **명명·정량화**했다:
- **Approval fatigue** — 인지 부담과 보안 보증의 tradeoff가 LLM 에이전트 보안의 중심 미해결 쟁점 (arXiv:2605.24309).
- **배포된 승인 게이트의 실측 실패** — Claude Code Auto Mode를 스트레스 조건에서 측정한 결과 오탐지율 81% (Anthropic 주장 17%와 대조, arXiv:2604.04978). **승인이 필요한 지점에서 게이트가 실패한다.**
- **blast-radius cue만으론 에이전트가 통제 안 됨** — 2,208개 프롬프트에서 경계 위반 55.8~67.8%. **"에이전트가 알아서 조심"은 해법이 아니다** (arXiv:2607.02294).

→ 따라서 설계 원칙은 **"승인 큐를 줄이는 것이 아니라, 처음부터 승인 큐에 안 들어오게 하는 것"** 이다. 이를 결정론적 정책 레이어로 강제한다.

---

## 2. 정책 분류의 기준 (blast radius + reversibility)

금액·빈도 기준은 이 도메인에서 **오작동**한다 (1원짜리 잘못된 알레르겐 매핑이 리콜 전파). 실효 기준은 **실수의 전염 불가능성(containment)** 이다.

| 축 | 값 | 의미 |
|---|---|---|
| **blast_radius** | `LOW` / `MEDIUM` / `HIGH` | 오류가 LOT 추적 체인·고객·규제 보고로 퍼지는 범위. `docs/02_flow.md` 양방향 추적 체인과 22종 알레르겐·전자세금계산서·식약처 보고를 전염 경로로 모델링 |
| **reversibility** | `REVERSIBLE` / `POLICY_REVERSIBLE` / `IRREVERSIBLE` | 되돌리는 비용. 취소 가능 vs 원복 비용 큼 vs 물리 흐름/규제 보고가 이미 붙음 |
| **regulatory_hook** | `NONE` / `MFDS_REPORT` / `RETENTION_2Y` | 법정 보고·보존 의무 여부. 있으면 다른 기준으로 대체 불가한 하드 게이트 |

### 전염 경로 3종 (docs/02_flow.md에서 유도)
1. **데이터 전염 (data poisoning)** — 잘못된 알레르겐 매핑 → 해당 원자재로 만든 모든 생산 LOT·고객에게 리콜 전파.
2. **의미 전염 (semantic misclassification)** — `ALLERG-16` 조개류 법정 그룹 내 세분류 오류 (법정 표시는 맞아 보이는데 특정 보유자에게 치명).
3. **물리 전염 (physical flow)** — 입고 차단을 엉뚱한 LOT에 걸면 생산 라인 정지; 반대로 놓치면 레시피 투입·출고.

---

## 3. 3등급 자율 (Graded Autonomy)

| 등급 | 정의 | 실행 방식 | 대상 액션 |
|---|---|---|---|
| **AUTONOMOUS** | 결과가 로그/뷰에만 영향, 역추적으로 원복 가능, 전염 경로 없음 | 결정론 게이트가 **허용** + 감사 로그만 | FEFO 권고 순서, 안전재고 알림 임계값 조정, 중복 알림 병합 |
| **POLICY_APPROVED** | 전염 경로가 있으나 역추적·원복 가능하고, **정책 조건이 명시적으로 전염 경로를 봉인** | 정책(룰) 충족 시 **자동 승인** + 감사; 조건 밖은 PRE_APPROVAL | 재발주 PO (기존 공급사 + 기존 단가 + 기존 수량범위 + 새 알레르겐 없음) |
| **PRE_APPROVAL** | 전염 시 역추적 비용 > 발생 비용, 규제 보고 붙음, 물리 흐름 변경 | **사람 승인 필수** (governance_actions PENDING) | 리콜 생성, 입고 차단/보류, 알레르겐 매핑, 신규 공급사 등록, `RECALLED` 전환 |

### SSOT 재분류 (docs/02_flow.md STEP별 에이전트 개입점 → 등급)

| 개입점 | 액션 | blast_radius | reversibility | regulatory | **등급** |
|---|---|---|---|---|---|
| STEP1 Procurement: 인증서 만료 감지·알림 | notify | LOW | REVERSIBLE | NONE | **AUTONOMOUS** |
| STEP1 Procurement: 만료 시 입고 차단 요청 | UPDATE inbound | HIGH | IRREVERSIBLE | MFDS_RETENTION | **PRE_APPROVAL (QC)** |
| STEP2 QC: 알레르겐 미매핑 검증 | 신규 원자재 등록 | HIGH | POLICY_REVERSIBLE | MFDS_REPORT | **PRE_APPROVAL** |
| STEP3 Procurement: PO 생성 | INSERT purchase_orders | MEDIUM | POLICY_REVERSIBLE | NONE | **POLICY_APPROVED** (조건 충족 시) / **PRE_APPROVAL** (조건 밖) |
| STEP4 QC: 온도 이탈·알레르기·인증서 이상 시 BLOCKED | UPDATE inbound | HIGH | IRREVERSIBLE | MFDS_REPORT | **PRE_APPROVAL (QC)** |
| STEP5 SupplyChain: FEFO 권고, 소진 예측 | — | LOW | REVERSIBLE | NONE | **AUTONOMOUS** |
| STEP5 SupplyChain: 재발주 요청 | 재발주 PO | MEDIUM | POLICY_REVERSIBLE | NONE | **POLICY_APPROVED** (조건 충족 시) |
| STEP7 SupplyChain: 안전재고 이하 생산 계획 알림 | — | LOW | REVERSIBLE | NONE | **AUTONOMOUS** |
| STEP10 QC: 리콜 Draft 생성 + ADMIN 승인 요청 | INSERT recalls | HIGH | IRREVERSIBLE | MFDS_REPORT + RETENTION_2Y | **PRE_APPROVAL (ADMIN)** |
| STEP10: `production_lots.status='RECALLED'` | UPDATE | HIGH | IRREVERSIBLE | MFDS_REPORT | **PRE_APPROVAL (ADMIN)** |

---

## 4. 속성(불변식) 정의 — 결정론적 검사기 입력

아래 불변식은 "모든 에이전트 행위에서 성립해야 하는 안전·품질 경계"다. `scripts/validate_governance_policy.py`가 사례 집합을 돌며 **어느 하나라도 깨지면 정책 설계 결함**을 보고한다. (속성은 라벨이 아니라 정책 자체를 검사 — 자기합리화 방지.)

| # | 불변식 (속성) | 이유 |
|---|---|---|
| `P1` | `blast_radius == HIGH → 등급 ≠ AUTONOMOUS` | 고위험 행위는 절대 자동 처리 불가 |
| `P2` | `regulatory_hook == MFDS_REPORT` 또는 `RETENTION_2Y` → 등급 = PRE_APPROVAL | 규제 게이트는 대체 불가 |
| `P3` | `reversibility == IRREVERSIBLE` → 등급 ≠ AUTONOMOUS` | 비가역 행위는 사전 승인 |
| `P4` | 등급 == `POLICY_APPROVED` → 정책 조건이 명시적으로 전염 경로를 봉인 (기존 공급사 + 기존 단가 + 기존 수량범위 + 새 알레르겐 없음) | 조건 밖이면 사전 승인 |
| `P5` | 사례 집합에서 AUTONOMOUS 비율 ≥ 하한(예: 40%) | 승인 피로도 상한 — "큐에 안 들어오게" 목표 |
| `P6` | 등급을 바꾸는 정책 변경 → 그 자체가 PRE_APPROVAL (사람) | 거버넌스의 거버넌스 — recursion 닫기 |
| `P7` | 행위가 데이터의 위반이 아닐 것 — 시퀀스 속성 (read-then-external-email 패턴 등) | 개별 툴콜은 무해, 위반은 시퀀스 속성 (Invariant/Runtime-on-Paths) |

---

## 5. 아키텍처 — 게이트 위치

리서치가 일관되게 확인: **게이트는 에이전트 안이 아니라 프록시/게이트웨이(경계)에 놓인다.** (Invariant Gateway, AWS AgentCore Gateway, Cerbos PDP 동일.)

```
[LLM 에이전트 (Claude Code/Codex)] ── mulino CLI ──▶ [L1 거버넌스 게이트웨이]
                                                          │  결정론적 평가 (blast/reversibility/regulatory)
                                                          ├── AUTONOMOUS ──────▶ 실행 + 감사로그
                                                          ├── POLICY_APPROVED ─▶ 조건 충족 → 실행, 미충족 → PENDING
                                                          └── PRE_APPROVAL ────▶ governance_actions PENDING → 사람 승인
```

- **결정적 계층은 에이전트 밖에 존재** — 에이전트가 자기 판단으로 게이트를 우회 불가.
- **속성 검사기** (`scripts/validate_governance_policy.py`)는 게이트의 **설계 검증**이자, 이후 CI에서 정책 변경 시 회귀 감시로 승격.
- **정책은 코드(git)로 관리** — DB에 두면 에이전트 자기 수정 위험·변경추적 어려움 (리서치 결론: "정책은 PR로 리뷰·머지").

---

## 6. 리서치와의 대응 (참고용 요약)

| 이 설계 개념 | 가장 가까운 선례 | 규칙 표현 |
|---|---|---|
| blast-radius 분류 | FAA Design Assurance Levels (A~E) | 심각도×강도 |
| 가역성 게이팅 | Dataiku "shadow mode" + 승인 임계 | business rules + orchestration |
| 결정론적 코어 | Drools Pragmatic AI / DecisionRules | DMN+PMML+결정표; "AI는 작성 보조지, 권위 아님" |
| 액션 레벨 강제 | OPA/Rego agent gate | Rego policy-as-code |
| 비가역·고전파행위 | Lean-Agent Protocol (금융, bleeding-edge) | Lean 4 정리증명 |
| 감사 가능성 | Pharma ALCOA+ | 데이터 무결성 규칙 집합 |

> **식품 안전 도메인의 빈자리**: 리서치 결과 식품 안전은 "LLM-게이트 규칙엔진"이 가장 얇은 영역 — 여전히 HACCP/FSMA 수기 절차, ML은 센서층. **"LLM이 제안하고 규칙이 알레르겐/이력추적을 게이트"하는 설계는 실제로 gap** 이며, 이 프로젝트가 채우는 차별화 지점.

---

## 7. 이후 작업 (이 PR 범위 밖)

1. **스키마 반영**: `governance_actions`에 `action_class`(`AUTONOMOUS/POLICY_APPROVED/PRE_APPROVAL`), `blast_radius`, `reversibility`, `regulatory_hook` 메타데이터 추가 여부 결정 (Phase 4/별도 PR).
2. **정책 조건 파일**: 재발주 PO 조건(공급사 등급/단가/수량범위/알레르겐)을 결정적 규칙으로 문서화 + 검사기 연동.
3. **CI 승격**: `verify_governance_policy.py`를 pre-commit/CI 게이트로 — 정책 변경 시 속성 회귀 자동 감시.
