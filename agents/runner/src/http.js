import { safeUrl } from './protocol.js';

export class HttpError extends Error {
  constructor(code, status = 0, backendCode = null) {
    super(code); this.code = code; this.status = status; this.backendCode = backendCode;
  }
}
const BACKEND_CODES = new Set(['COMPLETION_NOT_VERIFIED', 'INVALID_RESULT', 'STALE_LEASE', 'WORKER_ALREADY_LEASED']);

async function readJson(response, maxBytes) {
  const reader = response.body?.getReader();
  if (!reader) throw new HttpError('EMPTY_HTTP_RESPONSE');
  let size = 0;
  const chunks = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) throw new HttpError('HTTP_RESPONSE_TOO_LARGE');
      chunks.push(value);
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error instanceof HttpError ? error : new HttpError('INVALID_HTTP_RESPONSE');
  } finally { reader.releaseLock(); }
}

export class Auth0TokenClient {
  constructor({ issuer, clientId, clientSecret, audience = 'urn:mulino:erp-api', fetchImpl = fetch,
    now = Date.now, timeoutMs = 10_000, onToken = () => {} }) {
    this.issuer = safeUrl(issuer, { issuer: true });
    if (!clientId || !clientSecret || audience !== 'urn:mulino:erp-api') throw new Error('INVALID_AUTH_CONFIG');
    Object.assign(this, { clientId, clientSecret, audience, fetchImpl, now, timeoutMs, onToken });
    this.cached = null;
    this.pending = null;
  }

  async getToken({ signal, forceRefresh = false } = {}) {
    if (!forceRefresh && this.cached && this.now() < this.cached.refreshAt) return this.cached.token;
    if (!this.pending) this.pending = this.requestToken(signal).finally(() => { this.pending = null; });
    return this.pending;
  }

  async requestToken(signal) {
    this.cached = null;
    try {
      const response = await this.fetchImpl(`${this.issuer}/oauth/token`, {
        method: 'POST', redirect: 'error', signal: AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(this.timeoutMs)]),
        headers: { 'content-type': 'application/json' }, body: JSON.stringify({ grant_type: 'client_credentials',
          client_id: this.clientId, client_secret: this.clientSecret, audience: this.audience, scope: 'worker:dispatch' }),
      });
      if (!response.ok) { await response.body?.cancel(); throw new HttpError('AUTH_TOKEN_REJECTED', response.status); }
      const body = await readJson(response, 65536);
      if (typeof body.access_token !== 'string' || !body.access_token || body.access_token.length > 16384
        || body.token_type?.toLowerCase() !== 'bearer' || !Number.isFinite(body.expires_in) || body.expires_in <= 0
        || (body.scope !== undefined && (typeof body.scope !== 'string' || !body.scope.split(' ').includes('worker:dispatch')))) {
        throw new HttpError('INVALID_AUTH_TOKEN_RESPONSE');
      }
      const lifetime = Math.min(body.expires_in * 1000, 3_600_000);
      this.cached = { token: body.access_token, refreshAt: this.now() + lifetime - Math.min(30_000, lifetime / 10) };
      this.onToken(body.access_token);
      return body.access_token;
    } catch (error) {
      throw error instanceof HttpError ? error : new HttpError('AUTH_TOKEN_UNAVAILABLE');
    }
  }
}

export class WorkerApi {
  constructor({ baseUrl, tokenClient, fetchImpl = fetch, timeoutMs = 10_000 }) {
    this.baseUrl = safeUrl(baseUrl);
    Object.assign(this, { tokenClient, fetchImpl, timeoutMs });
  }

  async post(action, body, { idempotencyKey, signal } = {}) {
    if (!['claim', 'heartbeat', 'finish'].includes(action) || !idempotencyKey) throw new Error('INVALID_WORKER_REQUEST');
    try {
      for (let refresh = 0; refresh <= 1; refresh++) {
        const token = await this.tokenClient.getToken({ signal, forceRefresh: refresh === 1 });
        const response = await this.fetchImpl(`${this.baseUrl}/internal/runs/${action}`, {
          method: 'POST', redirect: 'error', signal: AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(this.timeoutMs)]),
          headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json', 'Idempotency-Key': idempotencyKey },
          body: JSON.stringify(body),
        });
        if (response.status === 401 && refresh === 0) { await response.body?.cancel(); continue; }
        if (response.status === 204) return null;
        if (!response.ok) {
          // Only a small allowlist of machine codes influences recovery. Never retain server text.
          const rejected = await readJson(response, 65536).catch(() => null);
          const backendCode = BACKEND_CODES.has(rejected?.error) ? rejected.error : null;
          throw new HttpError('WORKER_REQUEST_REJECTED', response.status, backendCode);
        }
        return await readJson(response, 524288);
      }
    } catch (error) {
      throw error instanceof HttpError ? error : new HttpError('WORKER_REQUEST_UNAVAILABLE');
    }
  }
}
