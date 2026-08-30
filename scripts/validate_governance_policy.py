#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
validate_governance_policy.py — 거버넌스 정책 레이어의 결정론적 속성(불변식) 검사기 (PoC)

문서: docs/08_governance_policy_layer.md (Section 4, 7개 속성 P1-P7)

역할 (2026-08-30 개정 — 정직한 지위 명시):
  - **자기일관성 회귀 테스트**다. classify() 코드가 docs/08 §5의 분류 규칙과 일치하는지,
    문서-코드 동기화가 깨지는 변경을 잡는다. 안전성 증거가 아니다.
  - P1/P2/P3/P6/P7의 사례 검사는 classify()가 그 규칙으로 작성되어 있으므로
    위반이 원리상 나올 수 없다(동어반복). 유일하게 비자명한 집계 검사는 P5다.
  - 안전성·운영 측정은 L1 Phase 4+ (읽기 저널 기반 P7, P5 삼각측량)에서 온다.

실행:
    python3 scripts/validate_governance_policy.py [--cases N] [--seed S] [--verbose]

속성 요약:
    P1  blast_radius==HIGH  ->  등급 != AUTONOMOUS
    P2  regulatory_hook     ->  등급 == PRE_APPROVAL
    P3  reversibility==IRREVERSIBLE -> 등급 != AUTONOMOUS
    P4  POLICY_APPROVED -> 정책 조건(전염 봉인) 충족
    P5  AUTONOMOUS 비율 >= AUTONOMY_MIN (승인 피로도 상한 — 잠정값, docs/08 §5 조사 계획 참조)
    P6  정책 변경(action_class 재분류)은 PRE_APPROVAL (거버넌스의 거버넌스)
    P7  위반은 개별 행위가 아니라 시퀀스 속성 (read-then-external)

종료 코드: 0 = 자기일관성 PASS / 1 = 불일치 발견 (문서-코드 동기화 깨짐)
"""

import argparse
import dataclasses
import enum
import random
import sys

# ---------------------------------------------------------------------------
# 도메인 모델 (docs/08 §2, docs/02_flow.md SSOT 재분류표)
# ---------------------------------------------------------------------------


class BlastRadius(enum.Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Reversibility(enum.Enum):
    REVERSIBLE = "REVERSIBLE"
    POLICY_REVERSIBLE = "POLICY_REVERSIBLE"
    IRREVERSIBLE = "IRREVERSIBLE"


class RegulatoryHook(enum.Enum):
    NONE = "NONE"
    MFDS_REPORT = "MFDS_REPORT"
    RETENTION_2Y = "RETENTION_2Y"


class ActionClass(enum.Enum):
    AUTONOMOUS = "AUTONOMOUS"
    POLICY_APPROVED = "POLICY_APPROVED"
    PRE_APPROVAL = "PRE_APPROVAL"


@dataclasses.dataclass
class ActionSpec:
    """행위의 결정론적 속성. 이 Spec이 '정책' 이다."""

    name: str
    blast_radius: BlastRadius
    reversibility: Reversibility
    regulatory_hook: RegulatoryHook
    # POLICY_APPROVED 허용 조건: 전염 경로를 봉인하는 정책 조건이 충족되는가?
    policy_condition_met: bool = False
    # P6: 등급을 재분류하는 '정책 변경' 행위인가? (거버넌스의 거버넌스)
    is_policy_change: bool = False

    def classify(self) -> ActionClass:
        """3등급 분류 로직 (Section 3). 결정론적."""
        # P6: 정책 변경 행위는 대체 불가 → 항상 PRE_APPROVAL
        if self.is_policy_change:
            return ActionClass.PRE_APPROVAL
        # 규제 게이트는 대체 불가 → 항상 PRE_APPROVAL
        if self.regulatory_hook != RegulatoryHook.NONE:
            return ActionClass.PRE_APPROVAL
        # 고위험 / 비가역 → 사전 승인
        if self.blast_radius == BlastRadius.HIGH or self.reversibility == Reversibility.IRREVERSIBLE:
            return ActionClass.PRE_APPROVAL
        # 정책 조건 충족 시 자동 승인 (전염 경로 봉인)
        if self.policy_condition_met:
            return ActionClass.POLICY_APPROVED
        # 전염 경로가 있지만 조건 미충족 → 사전 승인
        if self.blast_radius == BlastRadius.MEDIUM or self.reversibility == Reversibility.POLICY_REVERSIBLE:
            return ActionClass.PRE_APPROVAL
        # 전염 경로 없음 → 자율
        return ActionClass.AUTONOMOUS


# ---------------------------------------------------------------------------
# SSOT 기반 시드 사례 (docs/02_flow.md STEP별 에이전트 개입점 재분류)
# ---------------------------------------------------------------------------

def seed_specs() -> list[ActionSpec]:
    return [
        # STEP1 Procurement: 인증서 만료 감지·알림
        ActionSpec("cert_expiry_notify", BlastRadius.LOW, Reversibility.REVERSIBLE, RegulatoryHook.NONE),
        # STEP1 Procurement: 만료 시 입고 차단 요청
        ActionSpec("cert_expiry_block_inbound", BlastRadius.HIGH, Reversibility.IRREVERSIBLE, RegulatoryHook.RETENTION_2Y),
        # STEP2 QC: 신규 원자재 알레르겐 미매핑 검증
        ActionSpec("raw_material_register", BlastRadius.HIGH, Reversibility.POLICY_REVERSIBLE, RegulatoryHook.MFDS_REPORT),
        # STEP3 Procurement: 재발주 PO (조건 충족) → POLICY_APPROVED
        ActionSpec("reorder_po", BlastRadius.MEDIUM, Reversibility.POLICY_REVERSIBLE, RegulatoryHook.NONE, policy_condition_met=True),
        # STEP3 Procurement: PO 생성 (조건 미충족) → PRE_APPROVAL
        ActionSpec("new_po_unvetted_supplier", BlastRadius.MEDIUM, Reversibility.POLICY_REVERSIBLE, RegulatoryHook.NONE),
        # STEP4 QC: 온도 이탈·알레르기·인증서 이상 시 BLOCKED
        ActionSpec("inbound_block", BlastRadius.HIGH, Reversibility.IRREVERSIBLE, RegulatoryHook.MFDS_REPORT),
        # STEP5 SupplyChain: FEFO 권고
        ActionSpec("fefo_recommend", BlastRadius.LOW, Reversibility.REVERSIBLE, RegulatoryHook.NONE),
        # STEP7 SupplyChain: 안전재고 이하 생산 계획 알림
        ActionSpec("safety_stock_alert", BlastRadius.LOW, Reversibility.REVERSIBLE, RegulatoryHook.NONE),
        # STEP10 QC: 리콜 Draft 생성 + ADMIN 승인 요청
        ActionSpec("recall_create", BlastRadius.HIGH, Reversibility.IRREVERSIBLE, RegulatoryHook.RETENTION_2Y),
        # STEP10: production_lots.status='RECALLED'
        ActionSpec("recall_lot", BlastRadius.HIGH, Reversibility.IRREVERSIBLE, RegulatoryHook.MFDS_REPORT),
        # P6: 정책 변경 (등급 재분류) — 반드시 PRE_APPROVAL
        ActionSpec("policy_change", BlastRadius.MEDIUM, Reversibility.REVERSIBLE, RegulatoryHook.NONE, is_policy_change=True),
    ]


# ---------------------------------------------------------------------------
# Generator: 시드 사례에서 무작위 변형 사례를 수천 개 생성 (property-based)
# ---------------------------------------------------------------------------
def generate_cases(specs: list[ActionSpec], n: int, rng: random.Random) -> list[ActionSpec]:
    cases: list[ActionSpec] = list(specs)  # 시드 포함

    def _w(choices_with_weights):
        """가중치 기반 단일 선택. choices_with_weights: [(값, 가중치), ...]"""
        population = [v for v, _ in choices_with_weights]
        weights = [w for _, w in choices_with_weights]
        return rng.choices(population, weights=weights, k=1)[0]

    # 현실적 ERP 워크로드 믹스 (docs/02_flow.md): 대부분의 에이전트 개입은 가벼운
    # 자율 후보(notify/recommend/alert/log)이고, 소수가 운영/중간, 극소수가 고위험이다.
    tiers = [
        ("lightweight", 0.62),  # ~62%: 자율 후보 지배
        ("operational", 0.25),  # ~25%: POLICY_APPROVED 후보 지배
        ("high_risk", 0.13),    # ~13%: PRE_APPROVAL 지배
    ]

    # 티어별 필드 가중치. 각 티어 내에서도 낮은 확률로 '반대' 값이 등장하도록 하여
    # P1/P2/P3/P7(안전 속성)이 모든 사례에서 계속 검증되게 한다 (예: lightweight 티어에서도
    # HIGH blast / IRREVERSIBLE / 규제 훅 사례가 가끔 생성되고, 이는 반드시 PRE_APPROVAL).
    tier_fields = {
        "lightweight": {
            "blast": [(BlastRadius.LOW, 0.92), (BlastRadius.MEDIUM, 0.05), (BlastRadius.HIGH, 0.03)],
            "rev": [(Reversibility.REVERSIBLE, 0.92), (Reversibility.POLICY_REVERSIBLE, 0.05), (Reversibility.IRREVERSIBLE, 0.03)],
            "reg": [(RegulatoryHook.NONE, 0.95), (RegulatoryHook.MFDS_REPORT, 0.025), (RegulatoryHook.RETENTION_2Y, 0.025)],
            "policy_condition_met": 0.10,
            "is_policy_change": 0.005,
        },
        "operational": {
            "blast": [(BlastRadius.LOW, 0.30), (BlastRadius.MEDIUM, 0.55), (BlastRadius.HIGH, 0.15)],
            "rev": [(Reversibility.REVERSIBLE, 0.30), (Reversibility.POLICY_REVERSIBLE, 0.55), (Reversibility.IRREVERSIBLE, 0.15)],
            "reg": [(RegulatoryHook.NONE, 0.90), (RegulatoryHook.MFDS_REPORT, 0.06), (RegulatoryHook.RETENTION_2Y, 0.04)],
            "policy_condition_met": 0.60,
            "is_policy_change": 0.03,
        },
        "high_risk": {
            "blast": [(BlastRadius.LOW, 0.12), (BlastRadius.MEDIUM, 0.28), (BlastRadius.HIGH, 0.60)],
            "rev": [(Reversibility.REVERSIBLE, 0.12), (Reversibility.POLICY_REVERSIBLE, 0.28), (Reversibility.IRREVERSIBLE, 0.60)],
            "reg": [(RegulatoryHook.NONE, 0.20), (RegulatoryHook.MFDS_REPORT, 0.40), (RegulatoryHook.RETENTION_2Y, 0.40)],
            "policy_condition_met": 0.30,
            "is_policy_change": 0.03,
        },
    }

    while len(cases) < n:
        tier = _w(tiers)
        f = tier_fields[tier]
        cases.append(ActionSpec(
            name=f"gen_{len(cases)}",
            blast_radius=_w(f["blast"]),
            reversibility=_w(f["rev"]),
            regulatory_hook=_w(f["reg"]),
            policy_condition_met=rng.random() < f["policy_condition_met"],
            is_policy_change=rng.random() < f["is_policy_change"],
        ))
    return cases


# ---------------------------------------------------------------------------
# 속성(불변식) 정의 — P1~P7
# ---------------------------------------------------------------------------

AUTONOMY_MIN = 0.40  # P5 하한: 승인 피로도 상한 — 잠정값(근거 미확정).
                     # docs/08 §5 삼각측량(식약처 통계 + 알람 피로 문헌 + 시나리오 파라미터)으로
                     # 시나리오의 함수로 대체 예정. 현재값은 docs/08 초안의 "예: 40%" 예시가 상수가 된 것.


def property_checks(spec: ActionSpec, cls: ActionClass) -> list[str]:
    """개별 사례에 대한 P1,P2,P3,P4,P6,P7. 위반 시 메시지 목록 반환."""
    violations: list[str] = []

    # P1: 고위험은 절대 자동 불가
    if spec.blast_radius == BlastRadius.HIGH and cls == ActionClass.AUTONOMOUS:
        violations.append(f"P1 violated: HIGH blast_radius '{spec.name}' classified AUTONOMOUS")

    # P2: 규제 훅 → 반드시 PRE_APPROVAL
    if spec.regulatory_hook != RegulatoryHook.NONE and cls != ActionClass.PRE_APPROVAL:
        violations.append(f"P2 violated: regulatory_hook={spec.regulatory_hook.value} '{spec.name}' not PRE_APPROVAL")

    # P3: 비가역 → 자동 불가
    if spec.reversibility == Reversibility.IRREVERSIBLE and cls == ActionClass.AUTONOMOUS:
        violations.append(f"P3 violated: IRREVERSIBLE '{spec.name}' classified AUTONOMOUS")

    # P4: POLICY_APPROVED → 정책 조건이 전염 경로를 봉인
    if cls == ActionClass.POLICY_APPROVED and not spec.policy_condition_met:
        violations.append(f"P4 violated: '{spec.name}' POLICY_APPROVED without sealed policy condition")

    # P6: 정책 변경 행위는 반드시 PRE_APPROVAL (거버넌스의 거버넌스)
    if spec.is_policy_change and cls != ActionClass.PRE_APPROVAL:
        violations.append(f"P6 violated: policy change '{spec.name}' not PRE_APPROVAL")

    # P7: 위반은 시퀀스 속성(read-then-external 등). 현재 구현은 근사가 아니라 동어반복이다 —
    #     classify()가 MEDIUM을 AUTONOMOUS로 반환할 수 없으므로 이 검사는 절대 실패하지 않는다.
    #     실구현은 docs/08 §5: 읽기 저널 기반 "승격 인용은 최근 N분 내 실제 읽기" 시간 의존 검사 (L1 Phase 4+).
    if cls == ActionClass.AUTONOMOUS and spec.blast_radius == BlastRadius.MEDIUM:
        violations.append(f"P7 violated: MEDIUM blast_radius '{spec.name}' classified AUTONOMOUS (sequence risk)")

    return violations


def aggregate_checks(specs: list[ActionSpec]) -> tuple[list[str], list[str]]:
    """집합 수준 속성 (P5). 전체 위반 + P5 위반 반환."""
    violations: list[str] = []
    p5_violations: list[str] = []

    n = len(specs)
    if n == 0:
        return [], []

    autonomous_count = sum(1 for s in specs if s.classify() == ActionClass.AUTONOMOUS)
    autonomy_ratio = autonomous_count / n
    if autonomy_ratio < AUTONOMY_MIN:
        p5_violations.append(
            f"P5 violated: autonomy ratio {autonomy_ratio:.2f} < {AUTONOMY_MIN} "
            f"({autonomous_count}/{n} AUTONOMOUS)"
        )
    return violations, p5_violations


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cases", type=int, default=5000, help="생성할 총 사례 수 (시드 포함)")
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    rng = random.Random(args.seed)
    seed_s = seed_specs()
    specs = generate_cases(seed_s, args.cases, rng)

    all_violations: list[str] = []
    p5: list[str] = []

    for s in specs:
        cls = s.classify()
        all_violations.extend(property_checks(s, cls))

    _, p5 = aggregate_checks(specs)
    all_violations.extend(p5)

    # 결과 출력
    print(f"사례 수: {len(specs)} (seed={args.seed}, target={args.cases})")
    cls_counts: dict[str, int] = {}
    for s in specs:
        k = s.classify().value
        cls_counts[k] = cls_counts.get(k, 0) + 1
    for k in ("AUTONOMOUS", "POLICY_APPROVED", "PRE_APPROVAL"):
        print(f"  {k:16s} {cls_counts.get(k, 0)}")

    if not all_violations:
        print("자기일관성 PASS (P1-P7): classify()가 docs/08 §5 분류 규칙과 일치. (안전성 증거 아님)")
        return 0

    print(f"\n속성 위반 {len(all_violations)}건:")
    for v in all_violations[:30]:
        print(f"  - {v}")
    if len(all_violations) > 30:
        print(f"  ... (외 {len(all_violations) - 30}건)")
    print("\n결론: 문서-코드 불일치. docs/08 §5 또는 classify()를 수정해야 한다.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
