import assert from 'node:assert/strict';
import { readFile, writeFile, access } from 'node:fs/promises';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createServer } from 'node:http';

const exec = promisify(execFile);
assert.equal(process.getuid(), 10001);
assert.match(await readFile('/proc/self/status', 'utf8'), /CapEff:\s+0+\n/);
await assert.rejects(writeFile('/opt/mulino/unauthorized', 'x'), error => ['EROFS', 'EACCES'].includes(error.code));
await assert.rejects(access('/var/run/docker.sock'));
await writeFile('/work/allowed', 'ok');
await writeFile('/tmp/allowed', 'ok');
await writeFile('/home/mulino/.codex/volume-check', 'ok');
for (const name of ['MULINO_WORKER_CLIENT_SECRET', 'DB_PASSWORD', 'OPENAI_API_KEY', 'MULINO_LEASE_TOKEN'])
  assert.equal(process.env[name], undefined);
assert.equal(process.env.MULINO_TOKEN, 'smoke-capability-only');
assert.ok((await readFile('/etc/ssl/certs/ca-certificates.crt', 'utf8')).includes('BEGIN CERTIFICATE'));
for (const role of ['orchestrator', 'supply-chain', 'procurement', 'qc'])
  assert.ok((await readFile(`/opt/mulino/skills/${role}/SKILL.md`, 'utf8')).includes('name:'));
assert.ok((await readFile('/opt/mulino/skills/runtime.md', 'utf8')).includes('waitingConditions'));
const schema = JSON.parse(await readFile('/opt/mulino/result.schema.json', 'utf8'));
assert.deepEqual(schema.required, ['outcome', 'summary', 'waitingConditions', 'resultRef']);

let requests = 0;
const response = '{"ref":"CASE-SMOKE","quantity":999999999999.999999,"id":9007199254740993}';
const server = createServer((req, res) => {
  requests++;
  assert.equal(req.url, '/api/v1/agent/cases/CASE-SMOKE');
  assert.equal(req.headers.authorization, 'Bearer smoke-capability-only');
  res.writeHead(200, { 'content-type': 'application/json' }).end(response);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
try {
  const { stdout, stderr } = await exec('mulino', ['case', 'show', 'CASE-SMOKE'], {
    env: { ...process.env, MULINO_API_URL: `http://127.0.0.1:${server.address().port}/api/v1` }, timeout: 12000,
  });
  assert.equal(stdout.trim(), response);
  assert.equal(stderr, '');
  assert.equal(requests, 1);
} finally { await new Promise(resolve => server.close(resolve)); }

const stalled = createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'application/json' }); res.write('{"partial":');
});
await new Promise(resolve => stalled.listen(0, '127.0.0.1', resolve));
try {
  const error = await exec('mulino', ['case', 'show', 'CASE-SMOKE'], {
    env: { ...process.env, MULINO_API_URL: `http://127.0.0.1:${stalled.address().port}/api/v1`, MULINO_API_TIMEOUT_MS: '80' },
    timeout: 3000,
  }).catch(error => error);
  assert.equal(error.code, 2);
  assert.equal(error.signal, null);
  assert.match(error.stderr, /API_TIMEOUT/);
} finally { stalled.closeAllConnections(); await new Promise(resolve => stalled.close(resolve)); }

// Validate the pinned CLI's real config parser without login or a model request.
const config = JSON.parse(process.env.MULINO_SMOKE_CONFIG);
const probe = exec('codex', ['exec', '--ignore-user-config', '--strict-config', '--skip-git-repo-check',
  '--ephemeral', '--sandbox=danger-full-access', '--config', 'approval_policy="never"',
  ...config.flatMap(value => ['--config', value]), '--model', 'smoke-model',
  '--config', 'model_provider="smoke"', '--config',
  'model_providers.smoke={name="Smoke",base_url="http://127.0.0.1:1",wire_api="responses",env_key="MULINO_INTENTIONALLY_MISSING"}',
  'No model request should be made.'], { timeout: 10000 });
probe.child.stdin.end();
const result = await probe.catch(error => error);
assert.ok(result.code, 'The deliberately missing fixture credential must stop startup');
assert.match(result.stderr, /MULINO_INTENTIONALLY_MISSING/);
assert.doesNotMatch(result.stderr, /unknown field|error loading|failed to parse/i);
process.stdout.write('{"checks":"uid,capabilities,readonly,tmpfs,no-socket,env,ca,roles,schema,linux-cli,exact-json,partial-body-timeout,codex-config","modelRequests":0}\n');
