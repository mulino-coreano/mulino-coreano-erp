const roles = new Set(['ORCHESTRATOR', 'SUPPLY_CHAIN', 'PROCUREMENT', 'QC']);
const outcomes = new Set(['DONE', 'WAITING', 'FAILED', 'ABORTED']);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const string = (value, max = 256) => typeof value === 'string' && value.length > 0 && value.length <= max;
const require = (condition, code) => { if (!condition) throw new Error(code); };

/** RFC3339 timestamps within Java Instant's offset/fraction range; Date.parse alone normalizes invalid dates. */
export function validInstant(value) {
  if (typeof value !== 'string') return false;
  const parts = /^(\d{4})-(\d{2})-(\d{2})[Tt](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?([Zz]|[+-](\d{2}):(\d{2}))$/.exec(value);
  if (!parts) return false;
  const [year, month, day, hour, minute, second] = parts.slice(1, 7).map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1]
    || hour > 23 || minute > 59 || second > 59) return false;
  if (parts[9] !== undefined) {
    const offsetHour = Number(parts[9]);
    const offsetMinute = Number(parts[10]);
    if (offsetHour > 18 || offsetMinute > 59 || (offsetHour === 18 && offsetMinute !== 0)) return false;
  }
  return Number.isFinite(Date.parse(value));
}

export function safeUrl(value, { issuer = false, container = false } = {}) {
  let url;
  try { url = new URL(value); } catch { throw new Error('INVALID_URL'); }
  const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
    || (container && url.hostname === 'host.docker.internal');
  require((url.protocol === 'https:' || (!issuer && local && url.protocol === 'http:'))
    && !url.username && !url.password && !url.search && !url.hash, 'UNSAFE_URL');
  if (issuer) require(url.pathname === '/', 'INVALID_ISSUER_PATH');
  return url.toString().replace(/\/$/, '');
}

export function readConfig(env = process.env) {
  const issuer = safeUrl(env.MULINO_AUTH_ISSUER, { issuer: true });
  const clientId = env.MULINO_WORKER_CLIENT_ID;
  const clientSecret = env.MULINO_WORKER_CLIENT_SECRET;
  const image = env.MULINO_RUNTIME_IMAGE;
  const authVolume = env.MULINO_CODEX_AUTH_VOLUME;
  const workerId = env.MULINO_WORKER_ID;
  const model = env.MULINO_CODEX_MODEL;
  require(string(clientId) && string(clientSecret, 8192) && string(workerId, 128), 'MISSING_WORKER_CONFIG');
  require(string(image) && /^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$/.test(image), 'INVALID_RUNTIME_IMAGE');
  require(string(authVolume) && /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(authVolume), 'INVALID_AUTH_VOLUME');
  require(string(model, 128) && /^[a-zA-Z0-9][a-zA-Z0-9._:/-]*$/.test(model), 'MISSING_MODEL');
  return { issuer, clientId, clientSecret, workerId, image, authVolume, model,
    audience: 'urn:mulino:erp-api', apiBase: safeUrl(env.MULINO_API_BASE ?? 'http://127.0.0.1:8080/api/v1'),
    agentApiUrl: safeUrl(env.MULINO_AGENT_API_URL ?? 'http://host.docker.internal:8080/api/v1', { container: true }) };
}

export function redact(value, secrets = []) {
  if (typeof value === 'string') {
    let clean = value;
    for (const secret of [...new Set(secrets.filter(s => typeof s === 'string' && s.length > 0))]
      .sort((a, b) => b.length - a.length)) clean = clean.split(secret).join('[REDACTED]');
    return clean.replace(/Bearer\s+[^\s"']+/gi, 'Bearer [REDACTED]')
      .replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[REDACTED]');
  }
  if (Array.isArray(value)) return value.map(item => redact(item, secrets));
  if (object(value)) return Object.fromEntries(Object.entries(value).map(([key, item]) => [redact(key, secrets), redact(item, secrets)]));
  return value;
}

export function validateClaim(value, now = Date.now()) {
  require(object(value), 'INVALID_CLAIM');
  for (const key of ['runRef', 'caseRef', 'workItemRef', 'leaseToken', 'capabilityToken']) {
    require(string(value[key], key.endsWith('Token') ? 8192 : 256), 'INVALID_CLAIM');
  }
  require(roles.has(value.agentKey) && value.runtime === 'CODEX' && object(value.context), 'INVALID_CLAIM');
  require(Number.isInteger(value.timeoutSeconds) && value.timeoutSeconds > 0 && value.timeoutSeconds <= 600, 'INVALID_CLAIM');
  require(validInstant(value.leaseExpiresAt)
    && Date.parse(value.leaseExpiresAt) > now, 'INVALID_CLAIM_LEASE');
  require(Buffer.byteLength(JSON.stringify(value.context)) <= 262144, 'CLAIM_CONTEXT_TOO_LARGE');
  return value;
}

export function validateResult(value) {
  require(object(value) && outcomes.has(value.outcome) && string(value.summary, 4000), 'INVALID_MODEL_RESULT');
  require(Object.keys(value).every(key => ['outcome', 'summary', 'waitingConditions', 'resultRef'].includes(key)), 'INVALID_MODEL_RESULT');
  require(value.resultRef === undefined || value.resultRef === null || string(value.resultRef, 256), 'INVALID_MODEL_RESULT');
  const waits = value.waitingConditions ?? [];
  require(Array.isArray(waits) && waits.length <= 16, 'INVALID_MODEL_RESULT');
  // This reference describes a wait already committed by the proposal API. It grants
  // no new wait: an active Run still rejects an empty WAITING result on the server.
  const persistedApproval = typeof value.resultRef === 'string' && /^APPROVAL-[1-9]\d*$/.test(value.resultRef);
  require(value.outcome === 'WAITING' ? waits.length > 0 || persistedApproval : waits.length === 0, 'INVALID_MODEL_RESULT');
  for (const wait of waits) {
    require(object(wait) && ['DEPENDENCY_DONE', 'SCHEDULED_TIME'].includes(wait.type)
      && object(wait.payload) && string(wait.reason, 1000)
      && Object.keys(wait).every(key => ['type', 'payload', 'reason'].includes(key)), 'INVALID_MODEL_RESULT');
    require(wait.type === 'DEPENDENCY_DONE' ? string(wait.payload.dependentWiRef)
      : validInstant(wait.payload.dueAt), 'INVALID_MODEL_RESULT');
    require(Object.keys(wait.payload).length === 1, 'INVALID_MODEL_RESULT');
  }
  return value;
}

export function terminalReceipt(value) {
  return object(value) && ((value.status === 'COMPLETED' && ['DONE', 'WAITING'].includes(value.outcome))
    || (['FAILED', 'ABORTED'].includes(value.status) && value.status === value.outcome));
}
