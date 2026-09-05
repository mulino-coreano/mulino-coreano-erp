import http from "node:http";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { createApiClient } from "./api-client.js";
import { createToolServer, scopeForTool } from "./tools.js";
import { createAuth0, AuthorizationError, requireScope } from "./auth/auth0.js";
export { readHttpConfig } from "./auth/config.js";

export function createHttpServer(config, { authFetch = fetch } = {}) {
  const auth = createAuth0(config, authFetch);
  const metadataPath = "/.well-known/oauth-protected-resource/mcp";
  const metadata = {
    resource: config.audience,
    authorization_servers: [config.issuer],
    scopes_supported: ["erp:read", "work:write", "offline_access"],
    bearer_methods_supported: ["header"],
    resource_name: "Mulino ERP",
  };
  function json(res, status, data, headers = {}) {
    res.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store", ...headers });
    res.end(JSON.stringify(data));
  }
  return http.createServer(async (req, res) => {
    try {
      const path = req.url?.split("?")[0];
      if (req.method === "GET" && [metadataPath, "/.well-known/oauth-protected-resource"].includes(path)) {
        return json(res, 200, metadata);
      }
      if (path !== "/mcp") return json(res, 404, { error: "not_found" });
      // ngrok may preserve the public Host or rewrite it to loopback; forwarded headers are never trusted.
      const publicHost = new URL(config.publicOrigin).host;
      const localHost = /^(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$/.test(req.headers.host ?? "");
      if ((!localHost && req.headers.host !== publicHost) || (req.headers.origin && req.headers.origin !== config.publicOrigin)) {
        return json(res, 403, { error: "forbidden_origin" });
      }
      const identity = await auth.authenticate(req.headers.authorization);
      let body;
      if (req.method === "POST") {
        let bytes = 0;
        const chunks = [];
        for await (const chunk of req) {
          bytes += chunk.length;
          if (bytes > 65536) return json(res, 413, { error: "request_too_large" });
          chunks.push(chunk);
        }
        try { body = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
        catch { return json(res, 400, { error: "invalid_json" }); }
      }
      // MCP batching is unsupported by this protocol version. Do not let an uninspected batch reach a tool.
      if (Array.isArray(body)) return json(res, 400, { error: "batch_not_supported" });
      const scope = body?.method === "tools/call" ? scopeForTool(body.params?.name) : "erp:read";
      requireScope(identity, scope);
      if (req.method !== "POST") return json(res, 405, { error: "method_not_allowed" }, { Allow: "POST" });
      const api = createApiClient({ base: config.apiBase, timeoutMs: config.timeoutMs, token: () => auth.exchange(identity, scope) });
      // A fresh stateless transport and closure per HTTP request prevent identity/session reuse.
      const server = createToolServer(api);
      const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
      res.once("close", () => { void server.close().catch(() => {}); });
      await server.connect(transport);
      await transport.handleRequest(req, res, body);
    } catch (error) {
      if (res.headersSent) return res.end();
      if (error instanceof AuthorizationError) {
        return json(res, error.status, { error: error.code }, {
          "WWW-Authenticate": `Bearer resource_metadata="${config.publicOrigin}${metadataPath}", error="${error.code}", scope="${error.scope}"`,
        });
      }
      json(res, 500, { error: "internal_error" });
    }
  });
}
