# Mulino Coreano — 거버넌스 리서치 근거 요약 (2026-08-23)

> 이 문서는 `docs/08_governance_policy_layer.md`의 설계 근거가 된 딥리서치(3개 스트림)의 소스와 핵심 결론을 정리한다. **보조 문서**이며, 설계 결정의 SSOT는 `docs/08` + `docs/02_flow.md`.

---

## 1. 리서치 질문

> "LLM 에이전트가 제안/행위를 만들고, **결정론적 규칙/정책 레이어가 게이트**"하는 패턴의 실무·학술 사례는 무엇이며, 우리의 blast-radius/가역성/3등급 자율 설계를 어떻게 강화하는가?

---

## 2. Stream 1 — 에이전틱 가드레일 / 정책 강제 시스템

### 핵심 결론
- **게이트는 에이전트 안이 아니라 프록시/게이트웨이(경계)에 놓인다.** (Invariant Gateway, AWS AgentCore Gateway, Cerbos PDP 동일)
- **개별 툴콜은 무해, 위반은 시퀀스의 속성** — 정적 RBAC로 못 막고 런타임 + path-aware 결정 게이트가 필요.
- **사람 승인은 별개 축** — 결정론 게이트(allow/deny) 위에 일부만 human pause.

| 시스템 | 게이트 위치 | 규칙 표현 | 등급 |
|---|---|---|---|
| Invariant Guardrails (OSS) | LLM+도구 사이 프록시 | Python DSL, 데이터흐름 제어 | C |
| NVIDIA NeMo Guardrails | 도구 입출력 사이 | YAML + Colang flows | B |
| AWS AgentCore Gateway+Policy | MCP 트래픽 경계 | Cedar 정책어 | B (2026-06 GA) |
| Cerbos (OSS) | 외부 stateless PDP | YAML RBAC/ABAC, 위임체인 | C |
| OpenAI Agents SDK | runner 루프 | `needs_approval` / guardrail 함수 | B |
| Runtime Governance on Paths (arXiv:2603.16586) | 개념 | 경로 의존 정책 함수 | D |
| AgentSpec (arXiv:2503.18666, ICSE'26) | DSL | trigger+predicate+enforcement | A/B |

---

## 3. Stream 2 — 규제/고신뢰 도메인의 LLM+룰 엔진 결합

| 이 설계 개념 | 선례 | 규칙 표현 |
|---|---|---|
| blast-radius 분류 | **FAA Design Assurance Levels** (A~E) | 심각도×강도 |
| 가역성 게이팅 | Dataiku "shadow mode" + 승인 임계 | business rules + orchestration |
| 결정론적 코어 | Drools Pragmatic AI / DecisionRules | DMN+PMML+결정표 |
| 액션 레벨 강제 | OPA/Rego agent gate | Rego policy-as-code |
| 비가역·고전파행위 | Lean-Agent Protocol (금융) | Lean 4 정리증명 (bleeding-edge) |
| 감사 가능성 | Pharma ALCOA+ | 데이터 무결성 규칙 |
| 알레르겐/라벨 게이팅 | FSMA Preventive Controls | HACCP 알레르겐 교차접촉+표시 절차 |

**핵심**: DecisionRules "결정 로직은 검사 가능한 규칙 집합이어야지, 불투명한 모델 출력이면 안 된다 — 대출 승인을 판단하는 LLM은 규제 책임이다."
**식품 안전 빈자리**: "LLM-게이트 규칙엔진"은 식품 안전에서 가장 얇은 영역 — 여전히 HACCP/FSMA 수기절차, ML은 센서층. 우리 설계가 채우는 gap.

---

## 4. Stream 3 — 학술 (graded autonomy / 전문가시스템 부활 / 오토메이션 편향)

### 지지 근거
- **전문가시스템 부활 실재** (arXiv:2507.13550, 2606.16337): "LLM이 생성 → 결정론적 상징 레이어가 신뢰 경계" 패턴 활발.
- **blast-radius 형식화**: 최소 자율성 이론(2607.09744), 위임 보안+블래스트 반경 증명(2608.15888, 유출 75~100%→0%).
- **approval fatigue 명명·정량화** (arXiv:2605.24309): "인지 부담 vs 보안 보증" tradeoff가 업계 중심 쟁점.

### 경고 (설계를 찔림)
- **배포 승인 게이트 실측 실패** (arXiv:2604.04978): Claude Code Auto Mode 스트레스 조건에서 오탐지율 81% (Anthropic 주장 17% 대조). "승인이 필요한 지점에서 게이트가 실패한다."
- **blast-radius cue만으로 에이전트 통제 불가** (arXiv:2607.02294): 2,208 프롬프트에서 경계 위반 55.8~67.8%. → **속성은 모델 밖, 결정론적으로 강제되어야 함.**

### levels-of-autonomy 어휘
- arXiv:2510.17764 — L0~L3 등급 사다리, 각 등급에 허용 행동·위험 매핑.
- arXiv:2503.18778 — 위임 기준을 명시해 일부는 자율, 일부는 지원(보조).

---

## 5. 이 리서치가 강화하는 설계 결론

1. **게이트 위치**: 속성 검사기/거버넌스는 에이전트 밖 경계 게이트웨이여야 함.
2. **규칙 표현**: Rego/Cedar 정책-as-코드보다 데이터흐름/경로 규칙이 전염경로 모델과 더 부합.
3. **사람 승인은 별개 축**: 결정론 allow/deny + 상위 일부 human pause (OpenAI interruption 모델).
4. **식품·LLM 게이트 = 빈 틈새**: 포트폴리오 차별화 지점.

---

## 소스 신뢰도 등급
A = 피어리뷰·공식 문서 · B = 공식 문서/연구 · C = 애널리스트/벤더 · D = preprint/블로그. 상세 URL은 각 절 인용 링크 참조. 일부 벤더 시스템(1~5)은 생산 등급이지만 자사 보고 기반.
