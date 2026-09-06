import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { importJWK, SignJWT, jwtVerify } from "jose";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { createHttpServer, readHttpConfig } from "../../src/http.js";
import { Runner } from "../../../agents/runner/src/runner.js";
import { ProcessExecutor } from "../../../agents/runner/src/executor.js";
import {
  Auth0TokenClient,
  WorkerApi,
} from "../../../agents/runner/src/http.js";
const cfg = JSON.parse(await readFile(process.argv[2], "utf8"));
const issuer = "https://demo-auth.invalid/",
  resource = "https://demo-mcp.invalid/mcp",
  audience = "urn:mulino:erp-api";
const key = await importJWK(cfg.privateJwk, "RS256"),
  publicJwk = Object.fromEntries(
    Object.entries(cfg.privateJwk).filter(([k]) =>
      ["kty", "n", "e", "kid"].includes(k),
    ),
  ),
  publicKey = await importJWK(publicJwk, "RS256");
const token = (sub, aud, scope, extra = {}) =>
  new SignJWT({ sub, aud, iss: issuer, scope, ...extra })
    .setProtectedHeader({ alg: "RS256", kid: cfg.privateJwk.kid })
    .setIssuedAt()
    .setExpirationTime("5m")
    .sign(key);
let obo = 0,
  m2m = 0;
const authFetch = async (url, init) => {
  const u = new URL(url);
  assert.equal(u.origin, "https://demo-auth.invalid");
  if (u.pathname === "/.well-known/jwks.json")
    return Response.json({ keys: [publicJwk] });
  assert.equal(u.pathname, "/oauth/token");
  const p =
    init.headers["content-type"] === "application/json"
      ? JSON.parse(init.body)
      : Object.fromEntries(new URLSearchParams(init.body));
  if (p.grant_type === "client_credentials") {
    m2m++;
    assert.equal(p.client_id, "demo-worker");
    return Response.json({
      access_token: await token(
        "demo-worker@clients",
        audience,
        "worker:dispatch",
        { gty: "client-credentials", azp: "demo-worker" },
      ),
      token_type: "Bearer",
      expires_in: 300,
    });
  }
  obo++;
  const { payload } = await jwtVerify(p.subject_token, publicKey, {
    issuer,
    audience: resource,
  });
  return Response.json({
    access_token: await token(payload.sub, audience, p.scope),
    token_type: "Bearer",
    expires_in: 300,
    issued_token_type: "urn:ietf:params:oauth:token-type:access_token",
  });
};
const server = createHttpServer(
  readHttpConfig({
    MULINO_AUTH_ISSUER: issuer,
    MULINO_PUBLIC_ORIGIN: "https://demo-mcp.invalid",
    MULINO_AUTH0_CLIENT_ID: "demo-obo",
    MULINO_AUTH0_CLIENT_SECRET: "test-obo-only",
    MULINO_API_BASE: cfg.api,
  }),
  { authFetch },
);
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const clients = [];
async function session(role) {
  const jwt = await token(
    `auth0|${role}`,
    resource,
    "erp:read work:write procurement:decide",
  );
  const c = new Client({ name: "demo-e2e", version: "1" });
  clients.push(c);
  await c.connect(
    new StreamableHTTPClientTransport(
      new URL(`http://127.0.0.1:${server.address().port}/mcp`),
      {
        fetch: (u, i) => {
          const h = new Headers(i?.headers);
          h.set("Authorization", `Bearer ${jwt}`);
          return fetch(u, { ...i, headers: h });
        },
      },
    ),
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
    tokenClient: new Auth0TokenClient({
      issuer,
      clientId: "demo-worker",
      clientSecret: "test-m2m-only",
      fetchImpl: authFetch,
    }),
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
try {
  if (process.argv[3] === "due") {
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
      objective: `demo-e2e ${process.argv[3]} replenish DEMO-AMR and DEMO-BSC`,
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
    if (process.argv[3] === "block") {
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
    } else {
      const args = {
        approvalId,
        decision: "APPROVE",
        expectedVersion: approval.version,
        proposalHash: approval.proposalHash,
        reason: "Explicit simulated MANAGER fixture decision",
        requestKey: "demo-e2e-approve",
      };
      assert.equal(
        (await operator.callTool({ name: "decide_purchase", arguments: args }))
          .isError,
        true,
      );
      const approved = await call(manager, "decide_purchase", args),
        repeat = await call(manager, "decide_purchase", args);
      assert.deepEqual(repeat.purchaseOrderIds, approved.purchaseOrderIds);
      await step("DONE");
      await step("DONE");
      await step("IDLE");
      const final = await call(await session("MANAGER"), "get_case", {
        caseRef,
      });
      console.log("Final Case status", final.case.status);
      assert.equal(final.case.status, "WAITING");
      assert.equal(final.followups.length, 1);
      assert.equal(final.followups[0].agentKey, "ORCHESTRATOR");
      assert.ok(final.followups[0].dueAt);
      assert.ok(final.remainingObligations.length > 0);
      assert.ok(
        final.workItems.some(
          (w) => w.title === "Demo PROCUREMENT" && w.status === "DONE",
        ),
      );
      assert.ok(obo > 10);
      assert.ok(m2m > 0);
      console.log("DEMO_E2E_APPROVE_PASS");
      await writeFile(process.argv[2] + ".result", JSON.stringify({ caseRef }));
    }
  }
} finally {
  await Promise.all(clients.map((c) => c.close()));
  await new Promise((r) => {
    server.close(r);
    server.closeAllConnections();
  });
}
