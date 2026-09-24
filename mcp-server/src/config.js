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
