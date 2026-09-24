import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { Runner } from "../../../agents/runner/src/runner.js";
import { ProcessExecutor } from "../../../agents/runner/src/executor.js";
import { staticToken, WorkerApi } from "../../../agents/runner/src/http.js";
const cfg = JSON.parse(await readFile(process.argv[2], "utf8"));
const clients = [];
// PoC 로컬 신원: 역할마다 실제 stdio MCP 서버를 띄운다. Auth0/OBO는 #21·#22에서 보류했다.
async function session(role) {
  const c = new Client({ name: "demo-e2e", version: "1" });
  clients.push(c);
  await c.connect(
    new StdioClientTransport({
      command: process.execPath,
      args: [fileURLToPath(new URL("../../src/index.js", import.meta.url))],
      env: { PATH: process.env.PATH, MULINO_LOCAL_ROLE: role, MULINO_API_BASE: cfg.api },
    }),
  );
  return c;
}
async function call(c, name, args = {}) {
  const r = await c.callTool({ name, arguments: args });
  assert.ok(!r.isError, `${name}: ${JSON.stringify(r)}`);
  return r.structuredContent;
}
const executor = new ProcessExecutor({
  invocation: (claim) => ({
    command: process.execPath,
    args: [fileURLToPath(new URL("./model.mjs", import.meta.url))],
    env: {
      PATH: process.env.PATH,
      MULINO_TOKEN: claim.capabilityToken,
      MULINO_API_URL: cfg.api,
      DEMO_CLI: fileURLToPath(
        new URL("../../../agents/cli/zig-out/bin/mulino", import.meta.url),
      ),
      DEMO_PLAN_INPUT: JSON.stringify({
        warehouseId: cfg.warehouseId,
        productIds: cfg.productIds,
      }),
    },
  }),
});
const runner = new Runner({
  api: new WorkerApi({
    baseUrl: cfg.api,
    tokenClient: staticToken(cfg.workerToken),
  }),
  executor,
  workerId: "demo-e2e-worker",
  maxRunMs: 30000,
  logger: (m) => console.log(m),
});
async function step(expected) {
  const r = await runner.runOnce();
  console.log("Runner receipt", JSON.stringify(r));
  assert.equal(r.outcome ?? r.status, expected);
  return r;
}
const restartObjective = "demo-e2e restart replenish DEMO-AMR and DEMO-BSC";
const revisionAnswer =
  "Recalculate this Case using the changed supplier price; request a fresh purchase approval.";
async function recover(manager) {
  const cases = await call(manager, "list_cases", { productSku: "DEMO-AMR" });
  const found = cases.cases.find(
    (c) => c.objective === restartObjective || c.title === restartObjective,
  );
  assert.ok(found, "Recover durable Case by MCP search after process restart");
  const view = await call(manager, "get_case", { caseRef: found.caseRef });
  return { caseRef: found.caseRef, view };
}
try {
  if (["expire", "replan", "resume"].includes(process.argv[3])) {
    const manager = await session("MANAGER");
    let { caseRef, view } = await recover(manager);
    if (process.argv[3] === "expire") {
      const id = view.approvals[0].governanceActionId;
      const prior = await call(manager, "get_approval", { approvalId: id });
      const oldPlan = await call(manager, "get_plan", {
        planRef: prior.planRef,
      });
      const expired = await manager.callTool({
        name: "decide_purchase",
        arguments: {
          approvalId: id,
          decision: "APPROVE",
          expectedVersion: prior.version,
          proposalHash: prior.proposalHash,
          reason:
            "Fixture attempts old reviewed proposal after source mutation",
          requestKey: "demo-stale-approval",
        },
      });
      assert.equal(expired.isError, true);
      assert.equal(expired.structuredContent.requestKey, "demo-stale-approval");
      const old = await call(manager, "get_approval", { approvalId: id });
      assert.equal(old.status, "EXPIRED");
      assert.deepEqual(old.proposal, prior.proposal);
      assert.equal(old.proposalHash, prior.proposalHash);
      assert.deepEqual(old.purchaseOrderIds, []);
      await step("ABORTED"); // A continuously running worker observes expiration before the human answers.
      view = await call(manager, "get_case", { caseRef });
      const attention = view.attention.find(
        (a) => a.status === "OPEN" && !a.governanceActionId,
      );
      assert.ok(attention);
      const answer = await call(manager, "answer_attention", {
        attentionRequestId: attention.attentionRequestId,
        expectedVersion: attention.version,
        scope: "THIS_CASE",
        answer: `${revisionAnswer} Source plan: ${prior.planRef}.`,
        requestKey: "demo-expired-replan-answer",
      });
      assert.equal(answer.resume.status, "QUEUED");
      assert.ok(answer.resume.runRef);
      assert.equal(view.attention.filter(a=>a.status==="OPEN"&&!a.governanceActionId).length,1);
      assert.ok(answer.decisionId);
      await writeFile(
        process.argv[2] + ".prior",
        JSON.stringify({ oldPlan, prior }),
      );
      console.log("DEMO_E2E_EXPIRED_AND_EXPLICIT_REPLAN_PASS");
    } else if (process.argv[3] === "replan") {
      await step("WAITING");
      await step("DONE");
      await step("WAITING");
      await step("WAITING");
      ({ view } = await recover(manager));
      assert.equal(view.approvals.length, 2);
      const current = view.approvals.find((a) => a.status === "PENDING");
      assert.ok(current);
      const fresh = await call(manager, "get_approval", {
        approvalId: current.governanceActionId,
      });
      const prior = JSON.parse(
        await readFile(process.argv[2] + ".prior", "utf8"),
      );
      assert.notEqual(fresh.planRef, prior.prior.planRef);
      assert.notEqual(fresh.proposalHash, prior.prior.proposalHash);
      assert.equal(Number(fresh.proposal.totalKrw), 17000);
      const plan = await call(manager, "get_plan", { planRef: fresh.planRef });
      assert.equal(plan.version, prior.oldPlan.version + 1);
      assert.notEqual(plan.hash, prior.oldPlan.hash);
      assert.deepEqual(
        await call(manager, "get_plan", { planRef: prior.oldPlan.ref }),
        prior.oldPlan,
      );
      const oldApproval = await call(manager, "get_approval", {
        approvalId: prior.prior.id,
      });
      assert.equal(oldApproval.status, "EXPIRED");
      assert.deepEqual(oldApproval.proposal, prior.prior.proposal);
      assert.equal(oldApproval.proposalHash, prior.prior.proposalHash);
      console.log("DEMO_E2E_REVISED_PENDING_PASS");
    } else {
      const pending = view.approvals.find((a) => a.status === "PENDING");
      assert.ok(pending);
      const approval = await call(manager, "get_approval", {
        approvalId: pending.governanceActionId,
      });
      assert.equal(
        Number(approval.proposal.totalKrw),
        view.approvals.length === 2 ? 17000 : 16500,
      );
      const args = {
        approvalId: pending.governanceActionId,
        decision: "APPROVE",
        expectedVersion: approval.version,
        proposalHash: approval.proposalHash,
        reason: "Explicit fresh MANAGER approval after process restart",
        requestKey: "demo-restarted-approval",
      };
      const operator = await session("OPERATOR");
      assert.equal(
        (await operator.callTool({ name: "decide_purchase", arguments: args }))
          .isError,
        true,
      );
      const approved = await call(manager, "decide_purchase", args);
      assert.deepEqual(
        (await call(manager, "decide_purchase", args)).purchaseOrderIds,
        approved.purchaseOrderIds,
      );
      await step("DONE");
      await step("DONE");
      await step("IDLE");
      ({ view } = await recover(await session("MANAGER")));
      assert.equal(view.case.status, "WAITING");
      assert.equal(view.followups.length, 1);
      assert.equal(view.followups[0].agentKey, "ORCHESTRATOR");
      assert.ok(view.followups[0].dueAt);
      assert.ok(view.remainingObligations.length > 0);
      assert.ok(
        view.workItems.some(
          (w) => w.title === "Demo PROCUREMENT" && w.status === "DONE",
        ),
      );
      await writeFile(process.argv[2] + ".result", JSON.stringify({ caseRef }));
      console.log("DEMO_E2E_RESTART_RESUME_PASS");
    }
  } else if (process.argv[3] === "due") {
    const { caseRef } = JSON.parse(
      await readFile(process.argv[2] + ".result", "utf8"),
    );
    await step("IDLE");
    const manager = await session("MANAGER");
    const first = await call(manager, "get_case", { caseRef });
    const followup = first.followups[0];
    assert.ok(followup.attentionRequestId);
    await call(manager, "list_attention");
    await step("IDLE");
    const second = await call(await session("MANAGER"), "get_case", {
      caseRef,
    });
    assert.equal(
      second.followups[0].attentionRequestId,
      followup.attentionRequestId,
    );
    assert.equal(second.case.status, "WAITING");
    console.log("DEMO_E2E_DUE_PASS: one durable attention, no model dispatch");
  } else {
    const operator = await session("OPERATOR");
    assert.equal((await call(operator, "whoami")).role, "OPERATOR");
    const beforeAsk = await call(operator, "list_cases");
    await call(operator, "ask_inventory", { productQuery: "DEMO-AMR" });
    assert.deepEqual(await call(operator, "list_cases"), beforeAsk);
    const created = await call(operator, "create_case", {
      objective:
        process.argv[3] === "prepare"
          ? restartObjective
          : `demo-e2e ${process.argv[3]} replenish DEMO-AMR and DEMO-BSC`,
      requestKey:
        process.argv[3] === "block" ? "demo-e2e-block-case" : "demo-e2e-case",
      replenishment: {
        productSkus: ["DEMO-AMR", "DEMO-BSC"],
        warehouseId: cfg.warehouseId,
        targetDate: "2026-10-04",
      },
    });
    console.log("Created", JSON.stringify(created));
    const caseRef = created.caseRef;
    await step("WAITING");
    await step("DONE");
    await step("WAITING");
    await step("WAITING");
    const manager = await session("MANAGER");
    assert.equal((await call(manager, "whoami")).role, "MANAGER");
    const view = await call(manager, "get_case", { caseRef });
    console.log("Approval phase", caseRef);
    assert.equal(
      (await call(operator, "get_case", { caseRef })).case.caseRef,
      caseRef,
    );
    await call(manager, "list_cases");
    const approvalId =
        view.approvals[0].id ?? view.approvals[0].governanceActionId,
      approval = await call(manager, "get_approval", { approvalId });
    assert.equal(Number(approval.proposal.totalKrw), 16500);
    await call(manager, "get_plan", { planRef: approval.planRef });
    if (process.argv[3] === "prepare") {
      console.log("DEMO_E2E_PENDING_BEFORE_PROCESS_EXIT_PASS");
    } else if (process.argv[3] === "block") {
      const blocked = await call(manager, "decide_purchase", {
        approvalId,
        decision: "BLOCK",
        expectedVersion: approval.version,
        proposalHash: approval.proposalHash,
        reason: "Explicit simulated manager block",
        requestKey: "demo-e2e-block",
      });
      assert.equal(blocked.status, "BLOCKED");
      assert.deepEqual(blocked.purchaseOrderIds, []);
      await step("ABORTED");
      await step("IDLE");
      await step("IDLE");
      const blockedFinal = await call(manager, "get_case", { caseRef });
      assert.equal(blockedFinal.followups.length, 0);
      assert.equal(blockedFinal.approvals.length, 1);
      console.log("DEMO_E2E_BLOCK_PASS");
    }
  }
} finally {
  await Promise.all(clients.map((c) => c.close()));
}
