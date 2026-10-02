import { safeUrl } from './protocol.js';

export class HttpError extends Error {
  constructor(code, status = 0, backendCode = null) {
    super(code); this.code = code; this.status = status; this.backendCode = backendCode;
  }
}
const BACKEND_CODES = new Set(['COMPLETION_NOT_VERIFIED', 'INVALID_RESULT', 'STALE_LEASE', 'WORKER_ALREADY_LEASED']);

async function readJson(response, maxBytes, preserveNumbers = false) {
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
    return JSON.parse(Buffer.concat(chunks).toString('utf8'), preserveNumbers ? (key, value, context) => {
      // Do not round ERP NUMERIC/BIGINT facts when forwarding a claim to the model.
      if (typeof value === 'number' && (!Number.isSafeInteger(value) || /[.eE]/.test(context?.source ?? ''))) {
        if (!context?.source) throw new HttpError('UNSUPPORTED_JSON_RUNTIME');
        return context.source;
      }
      return value;
    } : undefined);
  } catch (error) {
    await reader.cancel().catch(() => {});
    throw error instanceof HttpError ? error : new HttpError('INVALID_HTTP_RESPONSE');
  } finally { reader.releaseLock(); }
}

/** PoC 로컬 실행기 토큰. Auth0 M2M(#21·#22에서 보류)을 대신하는 고정 bearer이다. */
export const staticToken = token => ({ getToken: async () => token });

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
        return await readJson(response, 524288, true);
      }
    } catch (error) {
      throw error instanceof HttpError ? error : new HttpError('WORKER_REQUEST_UNAVAILABLE');
    }
  }
}
