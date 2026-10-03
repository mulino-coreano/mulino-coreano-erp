import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import test from 'node:test';

const binary = process.env.MULINO_TEST_BINARY
  ? resolve(process.env.MULINO_TEST_BINARY)
  : fileURLToPath(new URL('../zig-out/bin/mulino', import.meta.url));
const token = 'smoke-capability-only-do-not-reflect';

async function endpoint(t, handle = (_request, response) => {
  response.writeHead(200, { 'content-type': 'application/json' });
  response.end('{"ok":true}');
}) {
  const requests = [];
  const server = createServer((request, response) => {
    const chunks = [];
    request.on('data', chunk => chunks.push(chunk));
    request.on('end', () => {
      const observed = {
        method: request.method,
        url: request.url,
        headers: request.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      };
      requests.push(observed);
      handle(observed, response);
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  return { requests, base: `http://127.0.0.1:${server.address().port}/api/v1` };
}

async function invoke(args, base, overrides = {}) {
  const env = {
    ...process.env,
    MULINO_API_URL: base,
    MULINO_TOKEN: token,
    MULINO_API_TIMEOUT_MS: '1500',
    ...overrides,
  };
  for (const name of Object.keys(env)) if (env[name] === undefined) delete env[name];
  return new Promise((resolve, reject) => {
    const child = spawn(binary, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
    const stdout = [];
    const stderr = [];
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error('CLI smoke child exceeded its five-second test deadline'));
    }, 5000);
    child.stdout.on('data', chunk => stdout.push(chunk));
    child.stderr.on('data', chunk => stderr.push(chunk));
    child.once('error', error => { clearTimeout(timer); reject(error); });
    child.once('close', (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal, stdout: Buffer.concat(stdout).toString('utf8'), stderr: Buffer.concat(stderr).toString('utf8') });
    });
  });
}

function success(result) {
  assert.equal(result.signal, null, `CLI terminated unexpectedly: ${result.stderr.replaceAll(token, '[REDACTED]')}`);
  assert.equal(result.code, 0, `CLI failed: ${result.stderr}`);
  assert.equal(result.stderr, '');
  return JSON.parse(result.stdout);
}

function failure(result, exitCode, httpStatus) {
  assert.equal(result.signal, null, `CLI terminated unexpectedly: ${result.stderr.replaceAll(token, '[REDACTED]')}`);
  assert.equal(result.code, exitCode);
  assert.equal(result.stdout, '', 'Failed commands must not publish a success payload');
  assert.ok(result.stderr.trim(), 'Errors must be JSON on stderr');
  const error = JSON.parse(result.stderr);
  assert.ok(error && typeof error === 'object' && !Array.isArray(error));
  if (httpStatus !== undefined) assert.equal(error.status, httpStatus);
  assert.ok(!result.stderr.includes(token), 'Error output reflected the bearer secret');
  assert.ok(!JSON.stringify(error).includes(token), 'Decoded error output reflected the bearer secret');
  return error;
}

for (const [args, path] of [
  [['case', 'show', 'CASE-DEMO'], '/api/v1/agent/cases/CASE-DEMO'],
  [['plan', 'show', 'PLAN-DEMO'], '/api/v1/agent/plans/PLAN-DEMO'],
  [['material', 'show', '9007199254740993'], '/api/v1/agent/materials/9007199254740993'],
  [['po', 'show', '9007199254740993'], '/api/v1/agent/purchase-orders/9007199254740993'],
]) {
  test(`${args.slice(0, 2).join(' ')} sends one authenticated scoped GET`, async t => {
    const server = await endpoint(t);
    assert.deepEqual(success(await invoke(args, server.base)), { ok: true });
    assert.equal(server.requests.length, 1);
    assert.equal(server.requests[0].method, 'GET');
    assert.equal(server.requests[0].url, path);
    assert.equal(server.requests[0].headers.authorization, `Bearer ${token}`);
    assert.equal(server.requests[0].body, '');
  });
}

test('reference characters are encoded within one path segment', async t => {
  const server = await endpoint(t);
  const reference = 'CASE-a/b?c#d%';
  success(await invoke(['case', 'show', reference], server.base));
  assert.equal(server.requests.length, 1);
  assert.equal(server.requests[0].url, `/api/v1/agent/cases/${encodeURIComponent(reference)}`);
});

for (const { name, args, body, path } of [
  { name: 'po propose', args: ['po', 'propose', 'PLAN/DEMO?x#%한'],
    body: '{ }', path: '/api/v1/plans/PLAN%2FDEMO%3Fx%23%25%ED%95%9C/purchase-proposal' },
  { name: 'plan calculate', args: ['plan', 'calculate', 'CASE-DEMO'],
    body: '{ "warehouseId": 1, "productIds": [1, 2], "horizonDays": 30 }', path: '/api/v1/cases/CASE-DEMO/plans' },
  { name: 'work create', args: ['work', 'create'],
    body: '{"caseRef":"CASE-DEMO","agentKey":"SUPPLY_CHAIN","title":"소요량 계산","metadata":{"quantity":9007199254740993.123456}}', path: '/api/v1/agent/work-items' },
  { name: 'work transition', args: ['work', 'transition', 'WI-SC'],
    body: '{"outcome":"WAITING","summary":"다음 단계 대기","waitingConditions":[{"type":"SCHEDULED_TIME","payload":{"dueAt":"2030-10-05T00:00:00Z"},"reason":"입고 확인"}]}', path: '/api/v1/agent/work-items/WI-SC/transition' },
]) {
  test(`${name} preserves its JSON body and idempotency key`, async t => {
    const server = await endpoint(t);
    const key = `smoke-${name.replaceAll(' ', '-')}`;
    success(await invoke([...args, '--json', body, '--request-key', key], server.base));
    assert.equal(server.requests.length, 1);
    const request = server.requests[0];
    assert.equal(request.method, 'POST');
    assert.equal(request.url, path);
    assert.equal(request.headers.authorization, `Bearer ${token}`);
    assert.match(request.headers['content-type'], /^application\/json(?:;|$)/i);
    assert.equal(request.headers['idempotency-key'], key);
    assert.equal(request.body, body, 'CLI must not round or reconstruct business JSON');
  });
}

test('successful JSON response preserves exact decimal digits', async t => {
  const response = '{"unitPrice":9007199254740993.123456,"quantity":0.000001,"status":"READY"}';
  const server = await endpoint(t, (_request, res) => {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.write(response.slice(0, 30));
    res.end(response.slice(30));
  });
  const result = await invoke(['plan', 'show', 'PLAN-DEMO'], server.base);
  success(result);
  assert.equal(result.stdout.trim(), response, 'Response decimals must remain byte-exact');
});

for (const verdict of ['PENDING_APPROVAL', 'BLOCKED']) {
  test(`${verdict} is successful business data, not a process failure`, async t => {
    const server = await endpoint(t, (_request, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ status: verdict }));
    });
    assert.equal(success(await invoke(['plan', 'show', 'PLAN-DEMO'], server.base)).status, verdict);
  });
}

test('invalid JSON and missing request keys fail locally before any request', async t => {
  const server = await endpoint(t);
  for (const args of [
    ['plan', 'calculate', 'CASE-DEMO', '--json', '{', '--request-key', 'invalid-json'],
    ['work', 'create', '--json', '{}'],
    ['work', 'transition', 'WI-SC', '--json', '{}', '--request-key', ''],
  ]) {
    failure(await invoke(args, server.base), 1);
    assert.equal(server.requests.length, 0);
  }
});

test('invalid URL configuration fails locally before any request', async t => {
  const server = await endpoint(t);
  for (const base of [
    'not-a-url',
    server.base.replace('http:', 'ftp:'),
    `${server.base}?query=not-allowed`,
    `${server.base}#fragment-not-allowed`,
    server.base.replace('http://', 'http://user:password@'),
  ]) {
    failure(await invoke(['case', 'show', 'CASE-DEMO'], base), 1);
    assert.equal(server.requests.length, 0);
  }
});

test('missing authentication and invalid timeout bounds fail before a request', async t => {
  const server = await endpoint(t);
  for (const overrides of [
    { MULINO_TOKEN: undefined },
    { MULINO_TOKEN: '' },
    { MULINO_API_TIMEOUT_MS: '0' },
    { MULINO_API_TIMEOUT_MS: '60001' },
    { MULINO_API_TIMEOUT_MS: 'not-a-number' },
  ]) {
    failure(await invoke(['case', 'show', 'CASE-DEMO'], server.base, overrides), 1);
    assert.equal(server.requests.length, 0);
  }
});

for (const status of [401, 403, 409, 503]) {
  test(`HTTP ${status} is a safe exit-2 error and is never retried`, async t => {
    const server = await endpoint(t, (_request, res) => {
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'REFLECTED_BACKEND_DETAIL', token }));
    });
    failure(await invoke(['case', 'show', 'CASE-DEMO'], server.base), 2, status);
    assert.equal(server.requests.length, 1);
  });
}

test('redirect responses are neither followed nor retried', async t => {
  const destination = await endpoint(t);
  const origin = await endpoint(t, (_request, res) => {
    res.writeHead(302, { location: `${destination.base}/should-not-receive-token` });
    res.end('redirect');
  });
  failure(await invoke(['case', 'show', 'CASE-DEMO'], origin.base), 2, 302);
  assert.equal(origin.requests.length, 1);
  assert.equal(destination.requests.length, 0, 'Redirect destination must never receive a request');
});

for (const escaped of [false, true]) {
  test(`successful response reflecting ${escaped ? 'escaped' : 'literal'} capability is rejected safely`, async t => {
    const reflected = escaped
      ? [...token].map(character => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`).join('')
      : token;
    const server = await endpoint(t, (_request, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(`{"reflected":"${reflected}","amount":9007199254740993.123456}`);
    });
    failure(await invoke(['plan', 'show', 'PLAN-DEMO'], server.base), 2);
    assert.equal(server.requests.length, 1);
  });
}

test('a non-JSON successful response is rejected without publishing its body', async t => {
  const server = await endpoint(t, (_request, res) => {
    res.writeHead(200, { 'content-type': 'text/html' });
    res.end(`<html>${token}</html>`);
  });
  failure(await invoke(['case', 'show', 'CASE-DEMO'], server.base), 2);
  assert.equal(server.requests.length, 1);
});

test('configured API timeout terminates one request without retry', async t => {
  const server = await endpoint(t, () => { /* Deliberately never answer. */ });
  const started = performance.now();
  failure(await invoke(['case', 'show', 'CASE-DEMO'], server.base, { MULINO_API_TIMEOUT_MS: '80' }), 2);
  assert.ok(performance.now() - started < 3000, 'Configured timeout must bound the operation');
  assert.equal(server.requests.length, 1);
});


test('HTTPS rejects an untrusted self-signed certificate before sending authorization', async t => {
  const directory = await mkdtemp(join(tmpdir(), 'mulino-cli-tls-'));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const keyPath = join(directory, 'key.pem');
  const certificatePath = join(directory, 'certificate.pem');
  // Test-only material, generated locally and deleted after the test. The certificate
  // matches the loopback address so rejection proves trust verification, not a name mismatch.
  await promisify(execFile)('openssl', [
    'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-nodes', '-days', '1',
    '-subj', '/CN=localhost', '-addext', 'subjectAltName=IP:127.0.0.1',
    '-keyout', keyPath, '-out', certificatePath,
  ], { timeout: 5000 });
  let connections = 0;
  let requests = 0;
  const server = createHttpsServer({ key: await readFile(keyPath), cert: await readFile(certificatePath) }, (_request, response) => {
    requests++;
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end('{"ok":true}');
  });
  server.on('connection', () => { connections++; });
  server.on('tlsClientError', () => { /* Expected certificate rejection. */ });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  t.after(async () => {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  });
  failure(await invoke(['case', 'show', 'CASE-DEMO'], `https://127.0.0.1:${server.address().port}/api/v1`), 2);
  assert.equal(connections, 1);
  assert.equal(requests, 0, 'Authorization must not be sent to an untrusted TLS peer');
});

test('an oversized response fails without publishing a partial JSON document', async t => {
  const server = await endpoint(t, (_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ padding: 'x'.repeat(1024 * 1024) }));
  });
  failure(await invoke(['case', 'show', 'CASE-DEMO'], server.base), 2);
  assert.equal(server.requests.length, 1);
});

test('API timeout also bounds a response that stalls after headers and partial body', async t => {
  const server = await endpoint(t, (_request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.flushHeaders();
    response.write('{"unfinished":');
  });
  const started = performance.now();
  failure(await invoke(['case', 'show', 'CASE-DEMO'], server.base, { MULINO_API_TIMEOUT_MS: '80' }), 2);
  assert.ok(performance.now() - started < 3000);
  assert.equal(server.requests.length, 1);
});

for (const [group, prefix] of [['material', 'materials'], ['po', 'purchase-orders']]) {
  test(`${group} identifiers stay within one encoded path segment`, async t => {
    const server = await endpoint(t);
    const identifier = '1/2?x#%한';
    success(await invoke([group, 'show', identifier], server.base));
    assert.equal(server.requests.length, 1);
    assert.equal(server.requests[0].url, `/api/v1/agent/${prefix}/${encodeURIComponent(identifier)}`);
  });
}

for (const status of ['PENDING_APPROVAL', 'NO_PURCHASE_REQUIRED']) {
  test(`po propose preserves ${status} and decimal source bytes without a follow-up request`, async t => {
    const response = `{"status":"${status}","totalAmount":9007199254740993.123456}`;
    const server = await endpoint(t, (_request, res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(response);
    });
    const result = await invoke(['po', 'propose', 'PLAN-DEMO', '--json', '{}', '--request-key', 'proposal-1'], server.base);
    assert.equal(success(result).status, status);
    assert.equal(result.stdout.trim(), response);
    assert.equal(server.requests.length, 1, 'A terminal proposal outcome must not trigger retry or another command');
  });
}

test('purchasing commands reject invented approval and malformed write arguments locally', async t => {
  const server = await endpoint(t);
  for (const args of [
    ['po', 'approve', '1'],
    ['po', 'approve', '1', '--json', '{}', '--request-key', 'k'],
    ['po', 'propose', 'PLAN-1', '--json', '{}'],
    ['po', 'propose', 'PLAN-1', '--json', '[]', '--request-key', 'k'],
    ['po', 'propose', 'PLAN-1', '--json', '{}', '--request-key', ''],
    ['po', 'propose', 'PLAN-1', '--json', '{}', '--json', '{}', '--request-key', 'k'],
    ['material', 'show', '1', '--json', '{}'],
  ]) failure(await invoke(args, server.base), 1);
  assert.equal(server.requests.length, 0);
});
