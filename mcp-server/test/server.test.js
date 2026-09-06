import assert from "node:assert/strict";
import http from "node:http";
import { spawn } from "node:child_process";
import path from "node:path";
import { afterEach, test } from "node:test";
import { fileURLToPath } from "node:url";

import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const PROJECT_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const resources = [];

afterEach(async () => {
  while (resources.length) {
    await resources.pop()();
  }
});

test("ask_inventory sends only the explicit product or SKU search term", async () => {
  let requestedUrl;
  let authorization;
  const apiServer = http.createServer((req, res) => {
    requestedUrl = req.url;
    authorization = req.headers.authorization;
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({
      answer: "1 inventory location",
      intent: "ASK",
      query: "AMR-200",
      inventory: [],
      provenance: "test",
      totalLocationCount: 1,
      returnedLocationCount: 1,
      truncated: false,
    }));
  });
  const apiBase = await listen(apiServer);
  resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: `${apiBase}/api/v1` });

  const result = await client.callTool({
    name: "ask_inventory",
    arguments: { productQuery: "AMR-200" },
  });

  assert.equal(result.isError, undefined);
  assert.equal(requestedUrl, "/api/v1/ask?q=AMR-200");
  assert.equal(authorization, "Bearer test-backend-credential");
});

test("list_cases accepts the valid argument-free MCP call", async () => {
  let requestedUrl;
  const apiServer = http.createServer((req, res) => {
    requestedUrl = req.url;
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("[]");
  });
  const apiBase = await listen(apiServer);
  resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: `${apiBase}/api/v1` });

  const result = await client.callTool({ name: "list_cases" });

  assert.equal(result.isError, undefined);
  assert.equal(requestedUrl, "/api/v1/cases");
});

test("list_cases rejects a status outside its declared contract", async () => {
  let requests = 0;
  const apiServer = http.createServer((req, res) => {
    requests++;
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end("[]");
  });
  const apiBase = await listen(apiServer);
  resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: `${apiBase}/api/v1` });

  const result = await client.callTool({
    name: "list_cases",
    arguments: { status: "OPEN&unexpected=true" },
  });

  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /status/);
  assert.equal(requests, 0);
});

test("case creation body timeout reports an uncertain mutation outcome without retrying", async () => {
  let requests = 0;
  let requestKey;
  const apiServer = http.createServer((req, res) => {
    requests++;
    requestKey = req.headers["idempotency-key"];
    req.resume();
    res.writeHead(200, { "Content-Type": "application/json" });
    res.flushHeaders();
    // Deliberately leave the body open: JSON parsing must share the API deadline.
  });
  const apiBase = await listen(apiServer);
  resources.push(() => closeServer(apiServer));
  const client = await connectClient({
    MULINO_API_BASE: `${apiBase}/api/v1`,
    MULINO_API_TIMEOUT_MS: "50",
  });

  const result = await within(
    client.callTool({
      name: "create_case",
      arguments: { objective: "Keep Amaretti in stock" },
    }),
    500,
    "connector did not enforce the configured API timeout",
  );

  assert.equal(result.isError, true);
  assert.match(result.content[0].text, /시간 초과/);
  assert.match(result.content[0].text, /반영되었을 수/);
  assert.match(result.content[0].text, /자동 재시도하지/);
  assert.equal(requests, 1);
  assert.match(requestKey ?? "", /^[0-9a-f-]{36}$/);
  assert.equal(result.structuredContent?.requestKey, requestKey);
});

test("create_case forwards explicit replenishment and preserves caller retry key and exact IDs", async () => {
  const calls = [];
  const apiServer = http.createServer(async (req, res) => {
    let body = "";
    for await (const chunk of req) body += chunk;
    calls.push({ key: req.headers["idempotency-key"], body: JSON.parse(body), method: req.method, path: req.url });
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end('{"caseRef":"CASE-1","title":"보충 목표","status":"OPEN","reused":'
      + (calls.length > 1 ? 'true' : 'false') + ',"metadata":{"replenishment":{"warehouseId":9007199254740993}}}');
  });
  const apiBase = await listen(apiServer);
  resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: `${apiBase}/api/v1` });
  const arguments_ = { objective: "AMR-200 보충", channel: "CHAT", requestKey: "replenishment-request-1",
    replenishment: { productSkus: ["AMR-200"], warehouseId: "9007199254740993", targetDate: "2026-10-01" } };

  const first = await client.callTool({ name: "create_case", arguments: arguments_ });
  const replay = await client.callTool({ name: "create_case", arguments: arguments_ });

  assert.equal(first.isError, undefined);
  assert.match(first.content[0].text, /^Case 접수됨:/);
  assert.match(replay.content[0].text, /^기존 Case에 연결됨:/);
  assert.equal(first.structuredContent.metadata.replenishment.warehouseId, "9007199254740993");
  assert.equal(first.structuredContent.requestKey, "replenishment-request-1");
  assert.deepEqual(calls, [1, 2].map(() => ({ key: "replenishment-request-1", method: "POST", path: "/api/v1/cases",
    body: { objective: "AMR-200 보충", channel: "CHAT", replenishment: arguments_.replenishment } })));
});

test("create_case generates a key once per invocation and omits unsupplied replenishment", async () => {
  const calls = [];
  const apiServer = http.createServer(async (req, res) => {
    let body = "";
    for await (const chunk of req) body += chunk;
    calls.push({ key: req.headers["idempotency-key"], body: JSON.parse(body) });
    res.writeHead(200, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ caseRef: "CASE-1", title: "goal", status: "OPEN", reused: false, metadata: {} }));
  });
  const apiBase = await listen(apiServer);
  resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: `${apiBase}/api/v1` });

  const first = await client.callTool({ name: "create_case", arguments: { objective: "goal" } });
  const second = await client.callTool({ name: "create_case", arguments: { objective: "goal" } });

  assert.equal(calls.length, 2);
  assert.match(calls[0].key ?? "", /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.notEqual(calls[0].key, calls[1].key);
  assert.deepEqual(calls[0].body, { objective: "goal", channel: "CHAT" });
  assert.equal(first.structuredContent.requestKey, calls[0].key);
  assert.equal(second.structuredContent.requestKey, calls[1].key);
});

test("create_case rejects malformed inputs and authority fields before contacting backend", async () => {
  let requests = 0;
  const apiServer = http.createServer((req, res) => { requests++; res.end("{}"); });
  const apiBase = await listen(apiServer);
  resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: `${apiBase}/api/v1` });
  const badArguments = [
    { requestKey: "" }, { requestKey: 123 }, { requestKey: "k".repeat(201) }, { requestKey: "   " },
    { requestKey: "unsafe\r\nheader" }, { objective: " " }, { objective: 7 }, { channel: "INVALID" },
    { userId: 1 }, { role: "MANAGER" },
    { replenishment: null }, { replenishment: {} }, { replenishment: { productSkus: [] } },
    { replenishment: { productSkus: Array(101).fill("SKU") } },
    { replenishment: { productSkus: ["s".repeat(51)] } },
    { replenishment: { productSkus: [" "] } }, { replenishment: { productSkus: [1] } },
    { replenishment: { productSkus: ["SKU"], warehouseId: 0 } },
    { replenishment: { productSkus: ["SKU"], warehouseId: 1.1 } },
    { replenishment: { productSkus: ["SKU"], warehouseId: 9007199254740992 } },
    { replenishment: { productSkus: ["SKU"], warehouseId: "-1" } },
    { replenishment: { productSkus: ["SKU"], warehouseId: "9223372036854775808" } },
    { replenishment: { productSkus: ["SKU"], targetDate: "2026-02-30" } },
    { replenishment: { productSkus: ["SKU"], targetDate: "tomorrow" } },
    { replenishment: { productSkus: ["SKU"], targetDate: "2026-09-05T00:00:00Z" } },
    { replenishment: { productSkus: ["SKU"], requestedBy: 1 } },
  ];
  for (const invalid of badArguments) {
    const result = await client.callTool({ name: "create_case", arguments: { objective: "goal", ...invalid } });
    assert.equal(result.isError, true, JSON.stringify(invalid));
  }
  assert.equal(requests, 0);
});

test("tool discovery describes explicit SKU extraction and only declares supported case inputs", async () => {
  const client = await connectClient({ MULINO_API_BASE: "http://127.0.0.1:1/api/v1" });
  const listing = await client.listTools();
  const tool = listing.tools.find(value => value.name === "create_case");
  assert.ok(tool.inputSchema.properties.requestKey);
  const schema = tool.inputSchema.properties.replenishment;
  assert.deepEqual(schema?.required, ["productSkus"]);
  assert.equal(schema.properties.productSkus.minItems, 1);
  assert.equal(schema.properties.productSkus.maxItems, 100);
  assert.equal(schema.properties.productSkus.items.maxLength, 50);
  assert.equal(tool.inputSchema.additionalProperties, false);
  assert.equal(schema.additionalProperties, false);
  assert.match(tool.description, /explicit|명시/);
  assert.equal(tool.annotations.readOnlyHint, false);
});

test("stdio refuses startup without an ERP credential", async () => {
  const child = spawn(process.execPath, ["src/index.js"], {
    cwd: PROJECT_DIR, env: { PATH: process.env.PATH }, stdio: ["pipe", "pipe", "pipe"],
  });
  resources.push(async () => child.kill());
  let stderr = "";
  child.stderr.on("data", (chunk) => { stderr += chunk; });
  const code = await within(new Promise((resolve) => child.once("exit", resolve)), 1000, "credential-less stdio remained available");
  assert.equal(code, 1);
  assert.match(stderr, /MULINO_API_TOKEN/);
});

async function connectClient(extraEnv) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: ["src/index.js"],
    cwd: PROJECT_DIR,
    env: { MULINO_API_TOKEN: "test-backend-credential", ...extraEnv },
    stderr: "pipe",
  });
  const client = new Client({ name: "mulino-mcp-test", version: "1.0.0" });
  await client.connect(transport);
  resources.push(() => client.close());
  return client;
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      resolve(`http://127.0.0.1:${address.port}`);
    });
  });
}

function closeServer(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
    server.closeAllConnections?.();
  });
}

function within(promise, timeoutMs, message) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(message)), timeoutMs)),
  ]);
}

test("conversation discovery exposes strict schemas, authority scopes and explicit human decision purpose", async () => {
  const client = await connectClient({ MULINO_API_BASE: "http://127.0.0.1:1/api/v1" });
  const { tools } = await client.listTools();
  for (const name of ["get_case", "get_plan", "get_approval", "get_purchase_order", "decide_purchase", "answer_attention"]) {
    const tool = tools.find(t => t.name === name);
    assert.equal(tool.inputSchema.additionalProperties, false);
    const scope = name === "decide_purchase" ? "procurement:decide" : name === "answer_attention" ? "work:write" : "erp:read";
    assert.deepEqual(tool._meta.securitySchemes, [{ type: "oauth2", scopes: [scope] }]);
    assert.equal(tool.annotations.readOnlyHint, scope === "erp:read");
  }
  assert.match(tools.find(t => t.name === "decide_purchase").description, /매번 인간의 명시적 선택/);
});

test("conversation reads preserve exact IDs and prices, disclose waits and approval evidence without mutations", async () => {
  const calls = [];
  const approval = { id: "9007199254740993", ref: "APPROVAL-9007199254740993", caseRef: "CASE-1", planRef: "PLAN-1", version: 2, proposalHash: "a".repeat(64), status: "PENDING", requiredRole: "MANAGER", proposal: { warehouseId: 1, totalKrw: "999999999999.123456", orders: [{ supplierId: 2, supplierName: "공급사", currency: "KRW", totalKrw: "999999999999.123456", lines: [{ materialId: 3, materialName: "밀가루", buyQuantity: "3.123456", buyUnit: "BAG", buyUnitPrice: "999.123456", baseQuantity: "30.123456", baseUnit: "KG", lineAmountKrw: "999999999999.123456" }] }] }, purchaseOrderIds: [] };
  const overview = { case: { caseRef: "CASE-1", title: "보충" }, summary: { state: "WAITING", needsHumanAttention: true, activeWorkCount: 1, remainingWorkCount: 2, nextActions: ["MANAGER 승인 필요"] }, participants: [{ caseParticipantId: 1, actorType: "HUMAN", userId: 1, userName: "담당자", role: "REQUESTER" }, { caseParticipantId: 2, actorType: "AGENT", agentId: 2, agentName: "PROCUREMENT", role: "OWNER" }], workItems: [{ assignedUserId: 1, assignedAgentId: 2, activeWaits: [{ reason: "승인 대기" }] }], attention: [{ attentionRequestId: 5, version: 1, governanceActionId: approval.id }], approvals: [{ governanceActionId: approval.id, proposalVersion: 2, proposalHash: approval.proposalHash, status: "PENDING", requiredRole: "MANAGER", totalKrw: "999999999999.123456", proposedOrders: approval.proposal.orders, purchaseOrders: [] }], plans: [{ ref: "PLAN-1" }], evidence: [{ source: "재고" }], remainingObligations: ["입고", "생산"] };
  const apiServer = http.createServer((req, res) => {
    calls.push({ path: req.url, method: req.method });
    const data = req.url.includes("overview") ? overview : req.url.includes("approvals") ? approval : req.url.includes("purchase-orders") ? { id: approval.id, status: "ORDERED", items: [] } : req.url.includes("plans") ? { ref: "PLAN-1", result: { total: "999999999999.123456" } } : [];
    res.setHeader("Content-Type", "application/json");
    // Real API sends NUMERIC and BIGINT as JSON number lexemes.
    res.end(JSON.stringify(data).replaceAll('"9007199254740993"', '9007199254740993').replaceAll('"999999999999.123456"', '999999999999.123456'));
  });
  const base = await listen(apiServer); resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: base + "/api/v1" });
  const c = await client.callTool({ name: "get_case", arguments: { caseRef: "CASE-1" } });
  assert.equal(c.isError, undefined);
  assert.deepEqual(c.structuredContent, overview);
  for (const term of ["담당자", "PROCUREMENT", "승인 대기", "입고", "생산", "MANAGER"]) assert.match(c.content[0].text, new RegExp(term));
  const a = await client.callTool({ name: "get_approval", arguments: { approvalId: approval.id } });
  assert.deepEqual(a.structuredContent, approval);
  for (const term of ["999999999999.123456", "3.123456", "999.123456", "공급사", "MANAGER", approval.proposalHash]) assert.ok(a.content[0].text.includes(term));
  await client.callTool({ name: "get_plan", arguments: { planRef: "PLAN-1" } });
  await client.callTool({ name: "get_purchase_order", arguments: { purchaseOrderId: approval.id } });
  await client.callTool({ name: "list_cases", arguments: { q: "보충 & 계획", productSku: "AMR-200", status: "WAITING" } });
  assert.deepEqual(calls.map(c => c.method), Array(5).fill("GET"));
  assert.equal(calls[0].path, "/api/v1/cases/CASE-1/overview");
  assert.equal(calls[1].path, "/api/v1/approvals/9007199254740993");
  assert.equal(calls[2].path, "/api/v1/plans/PLAN-1");
  assert.equal(calls[3].path, "/api/v1/purchase-orders/9007199254740993");
  const search = new URL(calls[4].path, base).searchParams;
  assert.equal(search.get("q"), "보충 & 계획"); assert.equal(search.get("productSku"), "AMR-200");
});

test("human writes send exact body and stable keys, retain error receipts, and never retry", async () => {
  const calls = [];
  const apiServer = http.createServer(async (req, res) => {
    let body = ""; for await (const chunk of req) body += chunk;
    calls.push({ path: req.url, method: req.method, key: req.headers["idempotency-key"], body: JSON.parse(body) });
    res.setHeader("Content-Type", "application/json");
    if (calls.length === 3) { res.statusCode = 409; return res.end("secret-backend-details"); }
    res.end(JSON.stringify(req.url.includes("decision") ? { error: "PROPOSAL_EXPIRED" } : { attentionRequestId: "9007199254740993", status: "ANSWERED", version: 2, answer: "다음 주", scope: "THIS_CASE", resume: { status: "QUEUED", runRef: "RUN-1" } }));
  });
  const base = await listen(apiServer); resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: base + "/api/v1" });
  const decision = { approvalId: "9007199254740993", decision: "APPROVE", expectedVersion: 2, proposalHash: "a".repeat(64), reason: "내용 확인", requestKey: "decision-1" };
  const result = await client.callTool({ name: "decide_purchase", arguments: decision });
  assert.equal(result.isError, true); assert.equal(result.structuredContent.error, "PROPOSAL_EXPIRED"); assert.equal(result.structuredContent.requestKey, "decision-1");
  assert.deepEqual(calls[0], { path: "/api/v1/approvals/9007199254740993/decision", method: "POST", key: "decision-1", body: { decision: "APPROVE", expectedVersion: 2, proposalHash: "a".repeat(64), reason: "내용 확인" } });
  const answer = await client.callTool({ name: "answer_attention", arguments: { attentionRequestId: "9007199254740993", answer: "다음 주", expectedVersion: 1, scope: "THIS_CASE" } });
  assert.equal(answer.structuredContent.requestKey, calls[1].key); assert.match(calls[1].key, /^[0-9a-f-]{36}$/);
  assert.deepEqual(calls[1].body, { answer: "다음 주", expectedVersion: 1, scope: "THIS_CASE" });
  assert.equal(calls[1].path, "/api/v1/attention/9007199254740993/answer"); assert.match(answer.content[0].text, /완료를 뜻하지/);
  const error = await client.callTool({ name: "decide_purchase", arguments: decision });
  assert.equal(error.isError, true); assert.equal(error.structuredContent.requestKey, "decision-1"); assert.match(error.content[0].text, /409/);
  assert.equal(JSON.stringify(error).includes("secret-backend-details"), false); assert.equal(calls.length, 3);
});

test("human tools reject invalid IDs, bounds, scopes, hashes and forged authority before backend", async () => {
  let count = 0;
  const apiServer = http.createServer((req, res) => { count++; res.end("{}"); });
  const base = await listen(apiServer); resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: base + "/api/v1" });
  const decision = { approvalId: 1, decision: "BLOCK", expectedVersion: 1, proposalHash: "a".repeat(64), reason: "거절" };
  const answer = { attentionRequestId: 1, answer: "확인", expectedVersion: 1, scope: "THIS_ACTION" };
  const variants = [{ expectedVersion: 0 }, { expectedVersion: 2147483648 }, { expectedVersion: "1" }, { expectedVersion: 1.5 }, { requestedBy: 1 }, { role: "MANAGER" }, { requestKey: " x" }];
  for (const [name, args, bad] of [["decide_purchase", decision, [...variants, { decision: "HOLD" }, { proposalHash: "A".repeat(64) }, { proposalHash: "a".repeat(63) }, { reason: " " }, { reason: "a".repeat(4001) }]], ["answer_attention", answer, [...variants, { scope: "ALWAYS" }, { answer: " " }, { answer: "a".repeat(8001) }, { governanceActionId: 1 }]]]) {
    for (const v of bad) assert.equal((await client.callTool({ name, arguments: { ...args, ...v } })).isError, true, JSON.stringify(v));
  }
  for (const id of [0, -1, 1.5, 9007199254740992, "9223372036854775808", "01", "1/decision", "1e3"]) {
    for (const [name, field] of [["get_approval", "approvalId"], ["get_purchase_order", "purchaseOrderId"]]) assert.equal((await client.callTool({ name, arguments: { [field]: id } })).isError, true);
  }
  for (const args of [{ q: " " }, { q: "x".repeat(201) }, { productSku: "x".repeat(51) }, { userId: 1 }]) assert.equal((await client.callTool({ name: "list_cases", arguments: args })).isError, true);
  for (const [name, field] of [["get_case", "caseRef"], ["get_plan", "planRef"]]) {
    for (const ref of [".", ".."]) {
      const result = await client.callTool({ name, arguments: { [field]: ref } });
      assert.equal(result.isError, true);
      assert.match(result.content[0].text, /점 구간/);
    }
  }
  assert.equal(count, 0);
});

test("human write boundary values match Java integer, signed long and text limits", async () => {
  const calls = [];
  const apiServer = http.createServer(async (req, res) => {
    let body = ""; for await (const chunk of req) body += chunk;
    calls.push({ path: req.url, body: JSON.parse(body) }); res.end("{}");
  });
  const base = await listen(apiServer); resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: base + "/api/v1" });
  for (const [name, args] of [
    ["decide_purchase", { approvalId: "9223372036854775807", decision: "BLOCK", expectedVersion: 2147483647, proposalHash: "f".repeat(64), reason: "가".repeat(4000) }],
    ["answer_attention", { attentionRequestId: "9223372036854775807", expectedVersion: 2147483647, answer: "가".repeat(8000), scope: "THIS_ACTION" }],
  ]) assert.equal((await client.callTool({ name, arguments: args })).isError, undefined);
  assert.equal(calls[0].body.reason.length, 4000); assert.equal(calls[1].body.answer.length, 8000);
  assert.ok(calls.every(call => call.path.includes("9223372036854775807")));
});

test("followup observations and answered attention preserve waiting responsibility and exact evidence", async () => {
  const followup = { ref: "FU-1", caseRef: "CASE-1", planRef: "PLAN-1", sourceWorkItemRef: "WI-2", workItemRef: "WI-3", parentWorkItemRef: "WI-1", agentKey: "ORCHESTRATOR", serverManaged: true, observationStatus: "RECEIPT_EXCEPTION", dueAt: "2026-10-06T00:00:00+09:00", observedAt: "2026-10-06T01:00:00+09:00", attentionRequestId: "9007199254740993", attentionStatus: "PENDING", observation: { receivedQuantity: "123456789012.123456" } };
  const overview = { case: { caseRef: "CASE-1" }, summary: { state: "WAITING", remainingWorkCount: 1 }, followups: [followup], remainingObligations: ["입고 확인 및 계획에 따른 생산/재고 검토"] };
  const calls = [];
  const apiServer = http.createServer(async (req, res) => {
    let body = ""; for await (const chunk of req) body += chunk;
    calls.push({ method: req.method, path: req.url, body: body ? JSON.parse(body) : null });
    res.setHeader("Content-Type", "application/json");
    if (req.method === "POST") {
      followup.attentionStatus = "ANSWERED";
      return res.end(JSON.stringify({ attentionRequestId: followup.attentionRequestId, status: "ANSWERED", version: 2, scope: "THIS_ACTION", answer: "부분 입고 확인, 잔량 확인 계속", resume: { status: "SERVER_MANAGED" } }));
    }
    res.end(JSON.stringify(overview).replaceAll('"9007199254740993"', '9007199254740993').replaceAll('"123456789012.123456"', '123456789012.123456'));
  });
  const base = await listen(apiServer); resources.push(() => closeServer(apiServer));
  const client = await connectClient({ MULINO_API_BASE: base + "/api/v1" });
  const before = await client.callTool({ name: "get_case", arguments: { caseRef: "CASE-1" } });
  assert.deepEqual(before.structuredContent, overview);
  for (const text of ["WAITING", "조정 담당", followup.dueAt, followup.observedAt, "입고 예외 확인 필요", "PENDING", "현재 재고 회복이나 Case 종결의 증거가 아닙니다"]) assert.ok(before.content[0].text.includes(text), text);
  const answer = await client.callTool({ name: "answer_attention", arguments: { attentionRequestId: followup.attentionRequestId, expectedVersion: 1, scope: "THIS_ACTION", answer: "부분 입고 확인, 잔량 확인 계속", requestKey: "followup-answer" } });
  assert.equal(answer.structuredContent.resume.status, "SERVER_MANAGED");
  assert.equal(answer.structuredContent.scope, "THIS_ACTION");
  assert.match(answer.content[0].text, /새 모델 실행은 예약하지 않으며/);
  assert.match(answer.content[0].text, /답변은 후속 업무를 완료하지 않습니다/);
  const after = await client.callTool({ name: "get_case", arguments: { caseRef: "CASE-1" } });
  assert.deepEqual(after.structuredContent, overview);
  assert.equal(after.structuredContent.summary.state, "WAITING");
  assert.equal(after.structuredContent.followups[0].attentionStatus, "ANSWERED");
  assert.match(after.content[0].text, /남은 검토:/);
  followup.observationStatus = "PRODUCTION_REVIEW"; followup.dueAt = null;
  const received = await client.callTool({ name: "get_case", arguments: { caseRef: "CASE-1" } });
  assert.equal(received.structuredContent.summary.state, "WAITING");
  assert.match(received.content[0].text, /확인 시각: 예약 없음/);
  assert.match(received.content[0].text, /생산\/재고 검토 필요/);
  assert.deepEqual(calls.map(c => c.method), ["GET", "POST", "GET", "GET"]);
  assert.deepEqual(calls[1].body, { answer: "부분 입고 확인, 잔량 확인 계속", expectedVersion: 1, scope: "THIS_ACTION" });
});
