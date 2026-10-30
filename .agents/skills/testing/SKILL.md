---
name: testing
description: Use when writing, reviewing, planning or pruning tests in this repository — unit tests, SIT/UAT scenario scripts (Gherkin), or deciding what a change needs tested. Tests here follow SAP Activate test types and must prove one of the project's six goals through business behaviour, never through message strings, labels or implementation details.
---

# Testing

## Mission

A test exists to prove that the system meets one of its goals. If you cannot
name the goal a test proves, do not write it. Fewer tests that each prove a
business behaviour beat many tests that re-state the implementation.

The design and its rationale are in
`docs/superpowers/specs/2026-09-25-scenario-tests-design.md`. This skill is
the working checklist; the spec wins if they disagree.

## 1. Start from purpose and goals

Purpose: in an ERP localised for Korean food regulation, humans and AI agents
continue the same Case, and every ERP write passes a human approval.

| # | Goal | Behaviour that proves it |
|---|---|---|
| 1 | LOT traceability chain never breaks | reverse trace to raw material and supplier, forward trace to customer; `SUM(outbound_lots.lot_quantity) = outbound.quantity`; input decrements `raw_material_lots.remaining_quantity` |
| 2 | No ERP write without human approval | PO needs MANAGER, inbound block/hold needs QC, recall needs ADMIN; rejection changes nothing; reads pass through |
| 3 | Replenishment calculation is correct | demand, BOM, stock, expected receipts and MOQ give the hand-calculated purchase candidates |
| 4 | Work continues | wait → event → resume; restart recovery; a failed Run asks a human and resumes from the answer |
| 5 | Agents do their role on any harness/model and cannot write outside it | role-scoped actions only; switching runtime/model does not change the business result |
| 6 | Korean regulation | 22 allergens; expired certificate blocks inbound, 30-day notice; recall records kept 2 years |

Before writing tests for a change, write down which goals it touches and the
behaviour you will prove for each. That list is the test plan.

## 2. Pick the test type (SAP Activate)

| Type | Use for | Form | Command |
|---|---|---|---|
| Unit | one goal's rule or invariant, deterministic | JUnit; name states goal and behaviour | `./gradlew test` |
| SIT | a business process end to end: real backend, `mulino` CLI, stdio MCP, runner; scripted agent (`model.mjs`) | Gherkin in `backend/src/test/resources/scenarios/` | `./gradlew sitTest` |
| UAT | the same scripts with a real harness and model (`MULINO_AGENT_RUNTIME`, `MULINO_AGENT_MODEL`); costs money | same Gherkin, `@uat` | `./gradlew uatTest` |
| Regression | all SIT before merge | — | `./gradlew sitTest` |

Cross-layer flows belong in SIT, not in a Unit test that stubs half the system.

## 3. Writing scenario scripts

- One `.feature` per business process (`mrp-p2p`, `qm-inbound-inspection`,
  `batch-recall`), `# language: ko`, header names the process, SAP module and
  goals.
- One scenario = one test case, tagged `@TC-<PROC>-NNN`, test types and module
  (`@TC-P2P-001 @sit @uat @MM`).
- Steps are business language and start with a role ("MANAGER가 구매 제안을
  승인한다"). No URLs, SQL, JSON or class names in a script — those live in
  step definitions.
- Every approval gate gets approve, reject and wrong-role cases.
- Numbers come from the fixture's hand calculation; SIT and UAT fix the
  business clock at the fixture date (2026-09-05, Asia/Seoul).
- 3–6 cases per process. Use a scenario outline only when the business rule
  changes, not to vary data.
- Features not built yet: write the script, tag `@pending @issue-<N>`. The
  feature issue is done when `@pending` is removed and SIT passes.

## 4. What a `그러면` / assertion may check

Allowed: business state the ERP keeps — PO count, approval status, plan
total, LOT remaining quantity, Case status, inbound blocked, recall created.

Forbidden:
- exact error/message text, labels, UI elements;
- "old string no longer shown" regression checks;
- which method or class was called;
- that a JSON field exists (check its value only when a decision uses it).

| Bad | Good |
|---|---|
| message is "Approval not found" | no purchase order was created |
| proposal JSON has `totalKrw` | proposal total is 16,500원 |
| error code `CMN009` | only one open Case exists for the product |

A UX check is valid only as behaviour: doing A causes B.

## 5. UAT specifics

- Missing login volume, image or model → report "skipped: prerequisite
  missing", not failed.
- Evidence per test case in `build/uat/<date>/<TC-ID>.json`: runtime, model,
  each Run's outcome and failure code, cost and tokens (from the runner's
  `model_finished` log), final business state.
- Real models vary: assert the business result, never the agent's steps.

## 6. Reviewing or pruning existing tests

Classify each test: keep (proves a goal), rewrite (right behaviour, asserts
text or field presence), move to SIT (cross-layer flow), delete (no goal,
duplicate, implementation detail). Show the classification table first;
delete only after the human approves. Record test count and run time before
and after.

## Never

- Add a test only because a string, label or constant changed.
- Assert error message text to prove a rejection — assert that nothing changed.
- Count a scripted-agent SIT pass as proof that a real harness works; that is
  what UAT is for.
- Run `uatTest` without saying it costs money.
