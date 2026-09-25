import test from 'node:test';
import assert from 'node:assert/strict';
import { readConfig, redact, validateClaim, validateResult } from '../src/protocol.js';
import { WorkerApi } from '../src/http.js';
import { claim, server, json } from './helpers.js';

const env = {
  MULINO_WORKER_TOKEN: 'worker-secret', MULINO_RUNTIME_IMAGE: 'mulino-runtime:local', MULINO_CODEX_MODEL: 'demo-model',
  MULINO_CODEX_AUTH_VOLUME: 'mulino-codex-auth', MULINO_WORKER_ID: 'worker-1',
};

test('configuration requires a worker token and restricts plaintext API to loopback', () => {
  assert.equal(readConfig(env)?.apiBase, 'http://127.0.0.1:8080/api/v1');
  assert.equal(readConfig({ ...env, MULINO_API_BASE: 'https://erp.example/api/v1' })?.apiBase, 'https://erp.example/api/v1');
  for (const bad of [
    { MULINO_API_BASE: 'http://remote.example/api/v1' },
    { MULINO_API_BASE: 'https://user:password@erp.example/api/v1' }, { MULINO_CODEX_AUTH_VOLUME: '/Users/me' },
    { MULINO_WORKER_TOKEN: '' }, { MULINO_RUNTIME_IMAGE: '--privileged' },
  ]) assert.throws(() => readConfig({ ...env, ...bad }));
});

test('claim rejects unknown runtime or role and missing/expired credentials', () => {
  assert.equal(validateClaim(claim())?.runRef, 'RUN-1');
  for (const bad of [{ runtime: 'CLAUDE' }, { agentKey: 'ADMIN' }, { context: null },
    { leaseToken: '' }, { capabilityToken: '' }, { timeoutSeconds: 601 }, { timeoutSeconds: -1 },
    { leaseExpiresAt: '2020-01-01T00:00:00Z' }, { caseRef: '' }]) {
    assert.throws(() => validateClaim(claim(bad)));
  }
});

test('a runner only accepts claims for its own runtime', () => {
  assert.equal(validateClaim(claim({ runtime: 'CLAUDE' }), Date.now(), 'CLAUDE')?.runtime, 'CLAUDE');
  assert.throws(() => validateClaim(claim(), Date.now(), 'CLAUDE'), /INVALID_CLAIM/);
});

test('structured result permits supported waits and rejects arbitrary output', () => {
  assert.equal(validateResult({ outcome: 'DONE', summary: 'Planned', resultRef: 'PLAN-1' })?.outcome, 'DONE');
  assert.equal(validateResult({ outcome: 'WAITING', summary: 'Waiting', waitingConditions: [
    { type: 'DEPENDENCY_DONE', payload: { dependentWiRef: 'WI-2' }, reason: 'Procurement' },
  ] })?.outcome, 'WAITING');
  for (const bad of [null, [], { outcome: 'DONE', summary: '' }, { outcome: 'DONE', summary: 'x', secret: 'x' },
    { outcome: 'WAITING', summary: 'x' }, { outcome: 'DONE', summary: 'x', waitingConditions: [{}] },
    { outcome: 'WAITING', summary: 'x', waitingConditions: [{ type: 'APPROVAL', payload: {}, reason: 'x' }] },
    { outcome: 'FAILED', summary: 'x'.repeat(4001) }]) assert.throws(() => validateResult(bad));
});

test('strict schema result accepts null reference and inconsistent terminal receipt is rejected', async () => {
  assert.equal(validateResult({ outcome: 'FAILED', summary: 'No result', waitingConditions: [], resultRef: null })?.resultRef, null);
  const { terminalReceipt } = await import('../src/protocol.js');
  assert.equal(terminalReceipt({ status: 'COMPLETED', outcome: 'FAILED' }), false);
});

test('HTTP rejects oversized response', async t => {
  const app = await server((req, res) => json(res, { context: 'x'.repeat(524289) }));
  t.after(app.close);
  const api = new WorkerApi({ baseUrl: `${app.url}/api/v1`, tokenClient: { getToken: async () => 'access' } });
  await assert.rejects(api.post('claim', {}, { idempotencyKey: 'k' }), /HTTP_RESPONSE_TOO_LARGE/);
});

test('redaction covers nested known secrets and JWT/bearer-like material', () => {
  const result = redact({ summary: 'worker-secret lease-token cap-token Bearer aaa.bbb.ccc', nested: ['worker-secret'] },
    ['worker-secret', 'lease-token', 'cap-token']);
  assert.doesNotMatch(JSON.stringify(result), /worker-secret|lease-token|cap-token|aaa\.bbb\.ccc/);
});

test('worker HTTP uses bearer and preserves idempotency key across token refresh', async t => {
  const calls = [];
  const app = await server((req, res, body) => {
    calls.push({ authorization: req.headers.authorization, key: req.headers['idempotency-key'], body });
    if (calls.length === 1) return json(res, {}, 401);
    res.writeHead(204).end();
  });
  t.after(app.close);
  const api = new WorkerApi({ baseUrl: `${app.url}/api/v1`, tokenClient: {
    getToken: async ({ forceRefresh } = {}) => forceRefresh ? 'new-token' : 'old-token',
  } });
  assert.equal(await api.post('claim', { workerId: 'w' }, { idempotencyKey: 'request-1' }), null);
  assert.equal(calls.length, 2);
  assert.deepEqual(calls.map(c => c.key), ['request-1', 'request-1']);
  assert.deepEqual(calls.map(c => c.authorization), ['Bearer old-token', 'Bearer new-token']);
});

test('claim business context preserves exact decimal and large integer source values', async t => {
  const app = await server((req, res) => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end('{"timeoutSeconds":600,"context":{"quantity":999999999999.999999,"productId":9007199254740993,"unitPrice":0.000001,"ratio":1e-6,"count":2}}');
  });
  t.after(app.close);
  const api = new WorkerApi({ baseUrl: `${app.url}/api/v1`, tokenClient: { getToken: async () => 'access' } });
  const value = await api.post('claim', {}, { idempotencyKey: 'numeric-claim' });
  assert.equal(value.timeoutSeconds, 600);
  assert.deepEqual(value.context, { quantity: '999999999999.999999', productId: '9007199254740993',
    unitPrice: '0.000001', ratio: '1e-6', count: 2 });
});

test('a server-issued approval reference can describe an already persisted wait without manufacturing conditions', () => {
  const result={ outcome:'WAITING',summary:'승인 대기가 서버에 저장되었습니다.',waitingConditions:[],resultRef:'APPROVAL-42' };
  assert.deepEqual(validateResult(result),result);
  for (const resultRef of [null,'PLAN-42','APPROVAL-0','APPROVAL-made-up'])
    assert.throws(()=>validateResult({...result,resultRef}), /INVALID_MODEL_RESULT/);
});

test('worker HTTP surfaces lease conflict without server secrets or automatic mutation retry', async t => {
  let calls = 0;
  const app = await server((req, res) => { calls++; json(res, { message: 'lease-secret' }, 409); });
  t.after(app.close);
  const api = new WorkerApi({ baseUrl: `${app.url}/api/v1`, tokenClient: { getToken: async () => 'access' } });
  await assert.rejects(api.post('heartbeat', {}, { idempotencyKey: 'k' }), error => error.status === 409 && !error.message.includes('lease-secret'));
  assert.equal(calls, 1);
});

test('waiting result permits 16 conditions and rejects 17 before submission', () => {
  const wait = { type: 'DEPENDENCY_DONE', payload: { dependentWiRef: 'WI-2' }, reason: 'Procurement' };
  assert.equal(validateResult({ outcome: 'WAITING', summary: 'Pending', waitingConditions: Array(16).fill(wait) }).waitingConditions.length, 16);
  assert.throws(() => validateResult({ outcome: 'WAITING', summary: 'Pending', waitingConditions: Array(17).fill(wait) }), /INVALID_MODEL_RESULT/);
});

test('scheduled waits require real RFC3339 instants with offsets and exact supported payload fields', () => {
  const result = dueAt => ({ outcome: 'WAITING', summary: 'Pending', waitingConditions: [
    { type: 'SCHEDULED_TIME', payload: { dueAt }, reason: 'Receipt' },
  ] });
  for (const date of ['2026-09-06T12:34:56Z', '2028-02-29T12:34:56.123456789+09:00', '2026-09-06t12:34:56z']) {
    assert.equal(validateResult(result(date)).waitingConditions[0].payload.dueAt, date);
  }
  for (const date of ['2026-09-06', '2026-09-06T12:34:56', '2026-02-30T00:00:00Z', '2026-09-06T24:00:00Z',
    '2026-09-06T00:00:00+18:01', '2026-09-06T00:00:00+23:00', '2026-09-06T00:00:00.1234567890Z']) {
    assert.throws(() => validateResult(result(date)), /INVALID_MODEL_RESULT/, date);
  }
  assert.throws(() => validateResult({ outcome: 'WAITING', summary: 'Pending', waitingConditions: [
    { type: 'SCHEDULED_TIME', payload: { due_at: '2026-09-06T00:00:00Z' }, reason: 'Receipt' },
  ] }), /INVALID_MODEL_RESULT/);
});

test('worker HTTP exposes only allowlisted backend rejection codes and ignores free text aliases', async t => {
  let body = { error: 'COMPLETION_NOT_VERIFIED', message: 'private-server-secret' };
  const app = await server((req, res) => json(res, body, 409));
  t.after(app.close);
  const api = new WorkerApi({ baseUrl: `${app.url}/api/v1`, tokenClient: { getToken: async () => 'access' } });
  await assert.rejects(api.post('finish', {}, { idempotencyKey: 'k' }), error =>
    error.backendCode === 'COMPLETION_NOT_VERIFIED' && !error.message.includes('private-server-secret'));
  body = { error: 'private-server-secret', message: 'COMPLETION_NOT_VERIFIED', code: 'INVALID_RESULT' };
  await assert.rejects(api.post('finish', {}, { idempotencyKey: 'k' }), error =>
    error.backendCode === null && !error.message.includes('private-server-secret'));
});
