import test from "node:test";
import assert from "node:assert/strict";
import { approvalText } from "../src/conversation-tools.js";

test("approval includes historical evidence, exact quantities, projected supply and uncertainty", () => {
  const text = approvalText({ planRef: "PLAN-OLD", requiredRole: "MANAGER", version: 2,
    proposalHash: "a".repeat(64), noActionConsequence: "입고가 늦어지면 목표 재고를 확보하지 못할 수 있습니다.",
    planEvidence: { planRef: "PLAN-OLD", version: 1, asOf: "2026-09-05T00:00:00Z", sourceHash: "source-hash",
      sourceRefs: ["stock:7"], supply: [
        { sourceRef: "stock:7", quantity: "12.345678", projected: false, item: { unit: "KG" } },
        { sourceRef: "po:8", quantity: "999999999999.123456", projected: true, item: { unit: "KG" } }],
      result: { forecasts: { 1: { dailyMean: "1.123456", safetyQuantity: "7.864192" } },
        requirements: { materials: [{ material: { id: 4, unit: "KG" }, grossQuantity: "20", suppliedQuantity: "12.345678", netQuantity: "7.654322" }] },
        purchases: [{ material: { id: 4 }, selection: { reason: "LOWEST_TOTAL_COST", warnings: [{ code: "RISK" }] } }], issues: [] } } });
  for (const expected of ["MANAGER", "2026-09-05", "source-hash", "stock:7", "계획 기준 재고", "예상 공급", "12.345678", "999999999999.123456", "7.654322", "LOWEST_TOTAL_COST", "RISK", "미조치 시", "확보하지 못할 수", "현재 재고", "불확실성"]) assert.ok(text.includes(expected), expected);
});

test("missing approval evidence is explicitly unavailable", () => {
  const text = approvalText({});
  assert.match(text, /계획 근거 미제공/);
  assert.match(text, /미조치 시: 미제공/);
});
