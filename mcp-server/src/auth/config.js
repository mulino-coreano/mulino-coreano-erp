function required(env, name) {
  const value = env[name];
  if (typeof value !== "string" || !value.trim()) throw new Error(`${name}: required configuration is missing.`);
  return value;
}

function httpsOrigin(value, name) {
  let url;
  try { url = new URL(value); } catch { throw new Error(`${name}: a valid HTTPS origin is required.`); }
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash || url.pathname !== "/") {
    throw new Error(`${name}: a valid HTTPS origin without credentials, path, query or fragment is required.`);
  }
  return url.origin;
}

export function readApiConfig(env = process.env) {
  const value = env.MULINO_API_BASE ?? "http://localhost:8080/api/v1";
  let url;
  try { url = new URL(value); } catch { throw new Error("MULINO_API_BASE: invalid URL."); }
  const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(url.hostname);
  if (url.username || url.password || url.search || url.hash || !(url.protocol === "https:" || (url.protocol === "http:" && loopback))) {
    throw new Error("MULINO_API_BASE: HTTPS or loopback HTTP URL without credentials, query or fragment is required.");
  }
  const timeout = Number(env.MULINO_API_TIMEOUT_MS ?? "10000");
  return { apiBase: value.replace(/\/$/, ""), timeoutMs: Number.isFinite(timeout) && timeout > 0 ? timeout : 10000 };
}

export function readHttpConfig(env = process.env) {
  const publicOrigin = httpsOrigin(required(env, "MULINO_PUBLIC_ORIGIN"), "MULINO_PUBLIC_ORIGIN");
  const issuer = httpsOrigin(required(env, "MULINO_AUTH_ISSUER"), "MULINO_AUTH_ISSUER") + "/";
  // Exact issuer matching is deliberate: Auth0 issuer identifiers include a trailing slash.
  if (env.MULINO_AUTH_ISSUER !== issuer) throw new Error("MULINO_AUTH_ISSUER: use the exact Auth0 issuer with a trailing slash.");
  const audience = env.MULINO_MCP_AUDIENCE ?? publicOrigin + "/mcp";
  if (audience !== publicOrigin + "/mcp") throw new Error("MULINO_MCP_AUDIENCE: must equal MULINO_PUBLIC_ORIGIN + /mcp.");
  const apiAudience = env.MULINO_API_AUDIENCE ?? "urn:mulino:erp-api";
  if (!apiAudience.trim() || apiAudience === audience) throw new Error("MULINO_API_AUDIENCE: a distinct ERP audience is required.");
  const host = env.MULINO_HOST ?? "127.0.0.1";
  const port = Number(env.MULINO_PORT ?? "3001");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("MULINO_PORT: invalid port.");
  return Object.freeze({
    publicOrigin, issuer, audience, apiAudience, host, port,
    clientId: required(env, "MULINO_AUTH0_CLIENT_ID"),
    clientSecret: required(env, "MULINO_AUTH0_CLIENT_SECRET"),
    ...readApiConfig(env),
  });
}
