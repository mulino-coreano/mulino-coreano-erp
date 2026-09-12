import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { DockerExecutor, ProcessExecutor } from '../src/executor.js';
import { codexConfiguration } from '../src/runtime-config.js';

const exec = promisify(execFile);
const authVolume = `mulino-smoke-${randomUUID()}`;
const image = process.argv[2] ?? 'mulino-codex-runtime:0.154.0';
const claim = { runRef: 'RUN-SMOKE', caseRef: 'CASE-SMOKE', workItemRef: 'WI-SMOKE',
  agentKey: 'SUPPLY_CHAIN', capabilityToken: 'smoke-capability-only', leaseToken: 'smoke-lease-host-only', context: {} };
const executor = new DockerExecutor({ image, authVolume, model: 'smoke-model' });
const env = { PATH: process.env.PATH };
const docker = (...args) => exec('docker', args, { env, timeout: 15000, maxBuffer: 131072 });
let handle;
let timedCleanup;
try {
  await docker('volume', 'create', authVolume);
  const spec = executor.buildInvocation(claim);
  const index = spec.args.indexOf('--entrypoint=codex');
  const args = [...spec.args.slice(0, index), '--network=none', '--env', 'MULINO_SMOKE_CONFIG',
    '--entrypoint=node', image, '--input-type=module'];
  const source = await readFile(new URL('../test/fixtures/runtime-smoke.mjs', import.meta.url), 'utf8');
  const child = spawn(spec.command, args, { env: { ...spec.env,
    MULINO_SMOKE_CONFIG: JSON.stringify(codexConfiguration(claim.agentKey)) }, stdio: ['pipe', 'pipe', 'pipe'] });
  const chunks = []; const errors = [];
  child.stdout.on('data', chunk => chunks.push(chunk));
  child.stderr.on('data', chunk => errors.push(chunk));
  const timer = setTimeout(() => {
    child.kill('SIGKILL');
    timedCleanup = spec.onCancel();
  }, 20000);
  child.stdin.end(source);
  const code = await new Promise((resolve, reject) => { child.once('error', reject); child.once('close', resolve); });
  clearTimeout(timer);
  assert.equal(code, 0, Buffer.concat(errors).toString());
  process.stdout.write(Buffer.concat(chunks));

  const hanging = executor.buildInvocation(claim);
  const entrypoint = hanging.args.indexOf('--entrypoint=codex');
  const name = hanging.args[hanging.args.indexOf('--name') + 1];
  const processExecutor = new ProcessExecutor({ invocation: () => ({ ...hanging,
    args: [...hanging.args.slice(0, entrypoint), '--network=none', '--entrypoint=node', image,
      '-e', 'setInterval(() => {}, 1000)'] }) });
  handle = processExecutor.start(claim);
  const deadline = Date.now() + 10000;
  let running = false;
  while (Date.now() < deadline && !running) {
    try { running = (await docker('inspect', '--format', '{{.State.Running}}', name)).stdout.trim() === 'true'; }
    catch { /* Container startup is asynchronous. */ }
    if (!running) await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(running, 'Smoke container did not start');
  await handle.cancel(); handle = null;
  const remaining = await docker('ps', '--all', '--filter', `name=^/${name}$`, '--format', '{{.ID}}');
  assert.equal(remaining.stdout.trim(), '', 'Cancelled container is still present');
  process.stdout.write('{"containerCancellation":"passed","modelRequests":0}\n');
} finally {
  if (handle) await handle.cancel();
  if (timedCleanup) await timedCleanup;
  await docker('volume', 'rm', authVolume);
}
