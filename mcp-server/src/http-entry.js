#!/usr/bin/env node
import { createHttpServer, readHttpConfig } from "./http.js";

try {
  const config = readHttpConfig();
  const server = createHttpServer(config);
  server.on("error", () => {
    console.error("MCP HTTP server could not start. Check MULINO_HOST/MULINO_PORT.");
    process.exitCode = 1;
  });
  server.listen(config.port, config.host, () => console.error("mulino-erp Streamable HTTP server running"));
  for (const signal of ["SIGINT", "SIGTERM"]) process.once(signal, () => {
    server.close();
    server.closeAllConnections();
  });
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
