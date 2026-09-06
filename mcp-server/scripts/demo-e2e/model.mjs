import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
let input = "";
for await (const chunk of process.stdin) input += chunk;
const claim = JSON.parse(input),
  c = claim.context;
assert.deepEqual(
  Object.keys(process.env).filter((k) => /TOKEN|SECRET|AUTH0|DB_/.test(k)),
  ["MULINO_TOKEN"],
);
const cli = (...a) =>
  JSON.parse(
    execFileSync(process.env.DEMO_CLI, a, {
      encoding: "utf8",
      env: process.env,
    }),
  );
const done = (ref = null) => ({
  outcome: "DONE",
  summary: "CLI verified persisted result",
  waitingConditions: [],
  resultRef: ref,
});
let result;
if (claim.agentKey === "ORCHESTRATOR") {
  if (c.purchasing?.some((p) => p.status === "BLOCKED")) {
    result = {
      outcome: "ABORTED",
      summary: "Human policy review required after BLOCK; no automatic reissue",
      waitingConditions: [],
      resultRef: null,
    };
  } else if (c.followups?.length) {
    assert.equal(c.followups[0].serverManaged, true);
    assert.ok(c.followups[0].dueAt);
    result = done();
  } else {
    const role = c.latestPlan ? "PROCUREMENT" : "SUPPLY_CHAIN";
    const w = cli(
      "work",
      "create",
      "--json",
      JSON.stringify({
        caseRef: claim.caseRef,
        agentKey: role,
        title: `Demo ${role}`,
      }),
      "--request-key",
      `${claim.caseRef}-${role}`,
    );
    result = {
      outcome: "WAITING",
      summary: "Waiting for assigned role",
      waitingConditions: [
        {
          type: "DEPENDENCY_DONE",
          payload: { dependentWiRef: w.workItemRef },
          reason: "Assigned role must finish",
        },
      ],
      resultRef: null,
    };
  }
} else if (claim.agentKey === "SUPPLY_CHAIN") {
  const p = cli(
    "plan",
    "calculate",
    claim.caseRef,
    "--json",
    process.env.DEMO_PLAN_INPUT,
    "--request-key",
    `${claim.caseRef}-plan`,
  );
  assert.equal(cli("plan", "show", p.ref).ref, p.ref);
  result = done(p.ref);
} else if (claim.agentKey === "PROCUREMENT") {
  const a = c.purchasing?.find((p) => p.purchaseOrderIds?.length);
  if (a) {
    for (const id of a.purchaseOrderIds) cli("po", "show", String(id));
    result = done(c.latestPlan.ref);
  } else {
    const p = cli(
      "po",
      "propose",
      c.latestPlan.ref,
      "--json",
      "{}",
      "--request-key",
      `${claim.caseRef}-purchase`,
    );
    assert.ok(p.executionResult);
    result = p.executionResult;
  }
} else throw Error("Unexpected role");
console.log(
  JSON.stringify({
    type: "item.completed",
    item: { type: "agent_message", text: JSON.stringify(result) },
  }),
);
