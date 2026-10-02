#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createApiClient } from "./api-client.js";
import { createToolServer } from "./tools.js";
import { readApiConfig } from "./config.js";

const role = process.env.MULINO_LOCAL_ROLE;
if (!["MANAGER", "OPERATOR", "QC", "ADMIN", "VIEWER"].includes(role)) {
  console.error("MULINO_LOCAL_ROLE: one of MANAGER, OPERATOR, QC, ADMIN, VIEWER is required for stdio.");
  process.exit(1);
}
let config;
try { config = readApiConfig(); } catch (error) {
  console.error(error.message);
  process.exit(1);
}
const server = createToolServer(createApiClient({ role, base: config.apiBase, timeoutMs: config.timeoutMs }));
await server.connect(new StdioServerTransport());
console.error("mulino-erp stdio MCP server running");
