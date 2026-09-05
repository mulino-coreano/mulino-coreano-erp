#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createApiClient } from "./api-client.js";
import { createToolServer } from "./tools.js";
import { readApiConfig } from "./auth/config.js";

const token = process.env.MULINO_API_TOKEN;
if (!token || !token.trim() || /\s/.test(token)) {
  console.error("MULINO_API_TOKEN: ERP API access token is required for stdio.");
  process.exit(1);
}
let config;
try { config = readApiConfig(); } catch (error) {
  console.error(error.message);
  process.exit(1);
}
const server = createToolServer(createApiClient({ token, base: config.apiBase, timeoutMs: config.timeoutMs }));
await server.connect(new StdioServerTransport());
console.error("mulino-erp stdio MCP server running");
