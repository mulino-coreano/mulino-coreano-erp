import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { ProcessExecutor, DockerExecutor } from '../src/executor.js';

const fixture = fileURLToPath(new URL('./fixtures/child.js', import.meta.url));
const claim = { runRef: 'RUN-1', caseRef: 'CASE-1', workItemRef: 'WI-1', agentKey: 'SUPPLY_CHAIN',
  capabilityToken: 'cap-secret', leaseToken: 'lease-secret', context: { objective: 'Exact objective' } };
const executor = (mode, extra = {}) => new ProcessExecutor({
  invocation: () => ({ command: process.execPath, args: [fixture, mode], env: { MULINO_TOKEN: 'cap-secret' } }), ...extra,
});

test('real child receives bounded context stdin and structured final result is parsed', async () => {
  const handle = executor('context').start(claim);
  assert.ok(handle);
  assert.deepEqual(await handle.result, { outcome: 'DONE', summary: 'Exact objective' });
});

test('real child gets explicit env only and known tokens are redacted from output', async () => {
  const handle = executor('env').start(claim, { secrets: ['cap-secret', 'lease-secret', 'secret-from-stderr'] });
  assert.ok(handle);
  const result = await handle.result;
  assert.doesNotMatch(result.summary, /cap-secret|lease-secret|MULINO_WORKER_CLIENT_SECRET|HOME|PATH/);
  assert.match(result.summary, /REDACTED/);
});

for (const [mode, code] of [['bad', 'INVALID_MODEL_JSON'], ['oversize', 'MODEL_OUTPUT_TOO_LARGE'], ['fail', 'MODEL_PROCESS_FAILED']]) {
  test(`real child ${mode} fails with bounded safe diagnostic`, async () => {
    const handle = executor(mode, { maxOutputBytes: 4096 }).start(claim);
    assert.ok(handle);
    await assert.rejects(handle.result, error => error.code === code && !error.message.includes('secret-from-stderr'));
  });
}

test('cancel terminates actual process and rejects once', async () => {
  const handle = executor('hang').start(claim);
  assert.ok(handle);
  const rejected = assert.rejects(handle.result, error => error.code === 'MODEL_CANCELLED');
  await handle.cancel('SHUTDOWN');
  await rejected;
  assert.throws(() => process.kill(handle.pid, 0), error => error.code === 'ESRCH');
});

test('Docker invocation isolates mounts, environment and immutable runtime configuration', () => {
  const docker = new DockerExecutor({ image: 'mulino-runtime:local', authVolume: 'mulino-codex-auth',
    agentApiUrl: 'http://host.docker.internal:8080/api/v1' });
  const spec = docker.buildInvocation(claim);
  assert.ok(spec);
  assert.equal(spec.command, 'docker');
  assert.equal(spec.args.filter(v => v === '--mount').length, 1);
  assert.ok(spec.args.includes('--read-only'));
  assert.ok(spec.args.includes('--cap-drop=ALL'));
  assert.ok(spec.args.includes('--security-opt=no-new-privileges'));
  assert.ok(spec.args.includes('--user=10001:10001'));
  assert.ok(spec.args.includes('--output-schema'));
  assert.ok(spec.args.includes('--json'));
  assert.ok(spec.args.includes('-'));
  const containerEnv = spec.args.flatMap((arg, index) => arg === '--env' ? [spec.args[index + 1]] : []);
  assert.deepEqual(containerEnv, [
    'MULINO_TOKEN', 'MULINO_API_URL=http://host.docker.internal:8080/api/v1',
    'CODEX_HOME=/home/mulino/.codex', 'HOME=/home/mulino',
  ]);
  assert.doesNotMatch(JSON.stringify(spec.args), /cap-secret|lease-secret|docker\.sock|\/Users\/|--privileged|--network=host/);
  assert.deepEqual(Object.keys(spec.env).sort(), ['MULINO_TOKEN', 'PATH']);
  assert.equal(spec.env.MULINO_TOKEN, 'cap-secret');
});
