#!/usr/bin/env node
// 시나리오의 사람 역할: 실제 stdio MCP 서버를 그 역할로 띄워 도구 하나를 호출하고 결과를 한 줄 JSON으로 낸다.
import { fileURLToPath } from "node:url";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

const [apiBase, role, tool, args = "{}"] = process.argv.slice(2);
const client = new Client({ name: "scenario-human", version: "1" });
await client.connect(new StdioClientTransport({
  command: process.execPath,
  args: [fileURLToPath(new URL("../../src/index.js", import.meta.url))],
  env: { PATH: process.env.PATH, MULINO_LOCAL_ROLE: role, MULINO_API_BASE: apiBase },
}));
try {
  const r = await client.callTool({ name: tool, arguments: JSON.parse(args) });
  process.stdout.write(JSON.stringify({ isError: Boolean(r.isError), content: r.structuredContent ?? null }) + "\n");
} finally {
  await client.close();
}
