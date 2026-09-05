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
