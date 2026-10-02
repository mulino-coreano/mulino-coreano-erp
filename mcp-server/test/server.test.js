import assert from "node:assert/strict";
import http from "node:http";
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
  const apiServer = http.createServer((req, res) => {
    requestedUrl = req.url;
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
  const apiServer = http.createServer((req, res) => {
    requests++;
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
});

async function connectClient(extraEnv) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: ["src/index.js"],
    cwd: PROJECT_DIR,
    env: extraEnv,
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
