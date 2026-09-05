import assert from "node:assert/strict";
import http from "node:http";
import { afterEach, test } from "node:test";
import { generateKeyPair, exportJWK, SignJWT, jwtVerify } from "jose";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { createHttpServer, readHttpConfig } from "../src/http.js";

const ISSUER = "https://tenant.example/";
const RESOURCE = "https://mulino.example/mcp";
const API_AUDIENCE = "urn:mulino:erp-api";
const ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
const resources = [];
afterEach(async () => { while (resources.length) await resources.pop()(); });

const env = {
  MULINO_AUTH_ISSUER: ISSUER,
  MULINO_PUBLIC_ORIGIN: "https://mulino.example",
  MULINO_AUTH0_CLIENT_ID: "test-obo-client",
  MULINO_AUTH0_CLIENT_SECRET: "test-client-secret",
};

test("configuration refuses insecure or ambiguous credential destinations", async () => {
  for (const change of [
    { MULINO_AUTH_ISSUER: "http://tenant.example/" },
    { MULINO_AUTH_ISSUER: "https://name:secret@tenant.example/" },
    { MULINO_PUBLIC_ORIGIN: "http://mulino.example" },
    { MULINO_PUBLIC_ORIGIN: "https://mulino.example/another-path" },
    { MULINO_MCP_AUDIENCE: "urn:wrong:resource" },
    { MULINO_API_AUDIENCE: RESOURCE },
    { MULINO_API_BASE: "http://public.example/api/v1" },
    { MULINO_AUTH0_CLIENT_SECRET: "" },
  ]) assert.throws(() => readHttpConfig({ ...env, ...change }));
});

test("unauthenticated MCP gets a discoverable challenge; metadata ignores forwarded host", async () => {
  const f = await fixture();
  const unauthorized = await fetch(f.url + "/mcp", { method: "POST" });
  assert.equal(unauthorized.status, 401);
  assert.match(unauthorized.headers.get("www-authenticate"), /resource_metadata="https:\/\/mulino.example\/\.well-known\/oauth-protected-resource\/mcp"/);
  for (const path of ["/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"]) {
    const response = await fetch(f.url + path, { headers: { "X-Forwarded-Host": "attacker.example" } });
    assert.equal(response.status, 200);
    const metadata = await response.json();
    assert.equal(metadata.resource, RESOURCE);
    assert.deepEqual(metadata.authorization_servers, [ISSUER]);
    assert.deepEqual(metadata.scopes_supported, ["erp:read", "work:write", "offline_access"]);
  }
  assert.equal(f.exchanges.length, 0);
});

test("signed JWT validation rejects issuer, audience, expiry, missing expiry, signature and service identities", async () => {
  const f = await fixture();
  const wrongKeys = await generateKeyPair("RS256");
  const badTokens = [
    "not-a-jwt",
    await f.token({ iss: "https://wrong.example/" }),
    await f.token({ aud: API_AUDIENCE }),
    await f.token({ exp: Math.floor(Date.now() / 1000) - 60 }),
    await f.token({ exp: undefined }),
    await f.token({}, wrongKeys.privateKey),
    await f.token({ sub: "worker@clients", gty: "client-credentials" }),
    await f.token({ gty: "client-credentials" }),
    await f.token({ sub: "worker@clients" }),
  ];
  for (const token of badTokens) {
    const response = await f.rpc(token, "tools/list");
    assert.equal(response.status, 401);
    const text = await response.text();
    assert.equal(text.includes(token), false);
  }
  const insufficient = await f.rpc(await f.token({ scope: "openid" }), "tools/list");
  assert.equal(insufficient.status, 403);
  assert.match(insufficient.headers.get("www-authenticate"), /insufficient_scope/);
  assert.equal(f.exchanges.length, 0);
});

test("real SDK lists existing tools plus whoami and exchanges only for each tool's required API scope", async () => {
  const f = await fixture();
  const incoming = await f.token();
  const client = await f.client(incoming);
  const listing = await client.listTools();
  assert.deepEqual(listing.tools.map((t) => t.name).sort(), ["ask_inventory", "create_case", "list_attention", "list_cases", "monitor_status", "whoami"]);
  const whoami = listing.tools.find((t) => t.name === "whoami");
  assert.equal(whoami.annotations.readOnlyHint, true);
  assert.deepEqual(whoami._meta.securitySchemes, [{ type: "oauth2", scopes: ["erp:read"] }]);
  assert.equal(listing.tools.find((t) => t.name === "create_case").annotations.readOnlyHint, false);
  assert.equal(listing.tools.find((t) => t.name === "monitor_status").annotations.readOnlyHint, true);
  assert.equal(f.exchanges.length, 0);
  const result = await client.callTool({ name: "whoami", arguments: { sub: "forged", role: "ADMIN" } });
  assert.equal(result.isError, undefined);
  assert.equal(result.structuredContent.name, "auth0|alice");
  assert.equal(f.backendCalls[0].path, "/api/v1/me");
  assert.notEqual(f.backendCalls[0].authorization, `Bearer ${incoming}`);
  assert.deepEqual(f.exchanges[0], {
    grant_type: "urn:ietf:params:oauth:grant-type:token-exchange",
    subject_token_type: ACCESS_TOKEN_TYPE,
    requested_token_type: ACCESS_TOKEN_TYPE,
    subject_token: incoming,
    client_id: "test-obo-client",
    client_secret: "test-client-secret",
    audience: API_AUDIENCE,
    scope: "erp:read",
  });
  assert.equal(JSON.stringify(result).includes(incoming), false);
});

test("each HTTP request revalidates credentials and concurrent users keep their own backend identities", async () => {
  const f = await fixture();
  let credential = await f.token();
  const alice = await f.client(() => credential);
  const bob = await f.client(await f.token({ sub: "auth0|bob" }));
  const results = await Promise.all([alice.callTool({ name: "whoami" }), bob.callTool({ name: "whoami" })]);
  assert.deepEqual(results.map((r) => r.structuredContent.name), ["auth0|alice", "auth0|bob"]);
  credential = await f.token({ exp: Math.floor(Date.now() / 1000) - 10 });
  await assert.rejects(() => alice.listTools(), (error) => error.code === 401);
  assert.equal(f.backendCalls.length, 2);
  credential = await f.token();
  assert.equal((await alice.callTool({ name: "whoami" })).structuredContent.name, "auth0|alice");
});

test("write scope is enforced before OBO or backend; validated write credentials reach create_case", async () => {
  const f = await fixture();
  const response = await f.rpc(await f.token(), "tools/call", { name: "create_case", arguments: { objective: "goal" } });
  assert.equal(response.status, 403);
  assert.match(response.headers.get("www-authenticate"), /work:write/);
  assert.equal(f.exchanges.length, 0);
  const client = await f.client(await f.token({ scope: "erp:read work:write" }));
  const result = await client.callTool({ name: "create_case", arguments: { objective: "goal" } });
  assert.equal(result.isError, undefined);
  assert.equal(f.exchanges[0].scope, "work:write");
  assert.equal(f.backendCalls[0].path, "/api/v1/cases");
});

test("failed or substituted OBO output never falls back to the incoming credential or leaks errors", async () => {
  const f = await fixture();
  const incoming = await f.token();
  const client = await f.client(incoming);
  for (const behavior of ["error", "wrong-subject", "wrong-audience", "wrong-type", "missing-scope", "expired"]) {
    f.exchangeBehavior = behavior;
    const result = await client.callTool({ name: "whoami" });
    assert.equal(result.isError, true, behavior);
    for (const secret of [incoming, "test-client-secret", "internal-upstream-secret"]) {
      assert.equal(JSON.stringify(result).includes(secret), false, behavior);
    }
  }
  assert.equal(f.backendCalls.length, 0);
});

test("backend errors are sanitized and backend/token redirects are not followed", async () => {
  const f = await fixture();
  const client = await f.client(await f.token());
  f.backendBehavior = "error";
  const error = await client.callTool({ name: "whoami" });
  assert.equal(error.isError, true);
  assert.equal(JSON.stringify(error).includes("internal-upstream-secret"), false);
  f.backendBehavior = "redirect";
  assert.equal((await client.callTool({ name: "whoami" })).isError, true);
  f.exchangeBehavior = "redirect";
  assert.equal((await client.callTool({ name: "whoami" })).isError, true);
  assert.equal(f.redirectCalls, 0);
});

test("untrusted browser origins and Host headers cannot use the MCP endpoint", async () => {
  const f = await fixture();
  const token = await f.token();
  for (const extra of [{ Origin: "https://attacker.example" }, { Host: "attacker.example" }]) {
    const status = await new Promise((resolve, reject) => {
      const request = http.request(f.url + "/mcp", { method: "POST", headers: { ...extra, Authorization: `Bearer ${token}` } }, (response) => {
        response.resume();
        resolve(response.statusCode);
      });
      request.on("error", reject);
      request.end();
    });
    assert.equal(status, 403);
  }
  assert.equal(f.exchanges.length, 0);
});

async function fixture() {
  const keys = await generateKeyPair("RS256");
  const jwk = { ...await exportJWK(keys.publicKey), kid: "test-key", alg: "RS256", use: "sig" };
  const state = { exchanges: [], backendCalls: [], exchangeBehavior: "ok", backendBehavior: "ok", redirectCalls: 0 };
  state.token = async (overrides = {}, key = keys.privateKey) => {
    const payload = { iss: ISSUER, aud: RESOURCE, sub: "auth0|alice", scope: "erp:read", iat: Math.floor(Date.now() / 1000), exp: Math.floor(Date.now() / 1000) + 300, ...overrides };
    if (payload.exp === undefined) delete payload.exp;
    return new SignJWT(payload).setProtectedHeader({ alg: "RS256", kid: "test-key" }).sign(key);
  };
  const upstream = http.createServer(async (req, res) => {
    res.setHeader("content-type", "application/json");
    if (req.url === "/.well-known/jwks.json") return res.end(JSON.stringify({ keys: [jwk] }));
    if (req.url === "/leak") { state.redirectCalls++; return res.end("{}"); }
    if (req.url === "/oauth/token") {
      let body = ""; for await (const chunk of req) body += chunk;
      const params = Object.fromEntries(new URLSearchParams(body));
      state.exchanges.push(params);
      if (state.exchangeBehavior === "error") { res.statusCode = 400; return res.end(JSON.stringify({ error_description: "internal-upstream-secret " + params.subject_token })); }
      if (state.exchangeBehavior === "redirect") { res.writeHead(307, { Location: "/leak" }); return res.end("{}"); }
      const { payload } = await jwtVerify(params.subject_token, keys.publicKey);
      const exchanged = await state.token({
        aud: state.exchangeBehavior === "wrong-audience" ? RESOURCE : API_AUDIENCE,
        sub: state.exchangeBehavior === "wrong-subject" ? "auth0|mallory" : payload.sub,
        scope: state.exchangeBehavior === "missing-scope" ? "" : params.scope,
        ...(state.exchangeBehavior === "expired" ? { exp: 1 } : {}),
      });
      return res.end(JSON.stringify({ access_token: exchanged, token_type: "Bearer", expires_in: 300, issued_token_type: state.exchangeBehavior === "wrong-type" ? "urn:id-token" : ACCESS_TOKEN_TYPE }));
    }
    state.backendCalls.push({ path: req.url, authorization: req.headers.authorization });
    if (state.backendBehavior === "error") { res.statusCode = 500; return res.end("internal-upstream-secret"); }
    if (state.backendBehavior === "redirect") { res.writeHead(307, { Location: "/leak" }); return res.end("{}"); }
    const { payload } = await jwtVerify(req.headers.authorization.slice(7), keys.publicKey, { issuer: ISSUER, audience: API_AUDIENCE });
    return res.end(JSON.stringify(req.url.endsWith("/cases")
      ? { caseRef: "CASE-1", title: "goal", status: "OPEN" }
      : { actorType: "HUMAN", userId: 1, name: payload.sub, role: "MANAGER", capabilities: ["erp:read"] }));
  });
  const upstreamUrl = await listen(upstream);
  const config = readHttpConfig({ ...env, MULINO_API_BASE: upstreamUrl + "/api/v1" });
  const server = createHttpServer(config, { authFetch: (url, init) => {
    const target = new URL(url);
    assert.equal(target.origin, "https://tenant.example");
    assert.ok(["/.well-known/jwks.json", "/oauth/token"].includes(target.pathname));
    return fetch(upstreamUrl + target.pathname, init);
  } });
  state.url = await listen(server);
  state.rpc = (token, method, params = {}) => fetch(state.url + "/mcp", {
    method: "POST", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
  });
  state.client = async (token) => {
    const client = new Client({ name: "auth-test", version: "1.0.0" });
    const transport = new StreamableHTTPClientTransport(new URL(state.url + "/mcp"), {
      fetch: (url, init) => {
        const headers = new Headers(init?.headers);
        headers.set("Authorization", `Bearer ${typeof token === "function" ? token() : token}`);
        return fetch(url, { ...init, headers });
      },
    });
    resources.push(() => client.close());
    await client.connect(transport);
    return client;
  };
  return state;
}

async function listen(server) {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  resources.push(() => new Promise((resolve) => { server.close(resolve); server.closeAllConnections(); }));
  return `http://127.0.0.1:${server.address().port}`;
}
