import test from 'node:test';
import assert from 'node:assert/strict';
import { DockerExecutor } from '../src/executor.js';
import { readConfig } from '../src/protocol.js';

const config = { image: 'mulino-runtime:test', authVolume: 'mulino-runtime-auth-test', model: 'demo-model' };
const claim = { agentKey: 'SUPPLY_CHAIN', capabilityToken: 'runtime-test-capability' };
const configs = spec => spec.args.flatMap((arg, i) => arg === '--config' ? [spec.args[i + 1]] : []);

test('runtime pins its role instructions and keeps the CLI capability in a narrow shell environment', () => {
  const spec = new DockerExecutor(config).buildInvocation(claim);
  assert.equal(spec.args[spec.args.indexOf('--model') + 1], 'demo-model');
  assert.ok(configs(spec).some(value => value.includes('developer_instructions=')
    && value.includes('/opt/mulino/skills/supply-chain/SKILL.md')));
  assert.ok(configs(spec).includes('shell_environment_policy.ignore_default_excludes=true'));
  const inherited = configs(spec).find(value => value.startsWith('shell_environment_policy.include_only='));
  assert.deepEqual(JSON.parse(inherited.split('=')[1]), ['PATH', 'HOME', 'CODEX_HOME', 'TMPDIR', 'MULINO_API_URL', 'MULINO_TOKEN']);
  assert.ok(configs(spec).includes('allow_login_shell=false'));
  assert.ok(configs(spec).includes('project_doc_max_bytes=0'));
  assert.ok(spec.args.includes('--sandbox=danger-full-access'));
  assert.ok(spec.args.includes('--read-only'));
  assert.ok(spec.args.includes('--cap-drop=ALL'));
  assert.doesNotMatch(JSON.stringify(spec.args), /runtime-test-capability/);
});

test('role paths come from an allowlist and cannot be supplied by Case content', () => {
  const executor = new DockerExecutor(config);
  for (const agentKey of ['ORCHESTRATOR', 'SUPPLY_CHAIN', 'PROCUREMENT', 'QC']) {
    const args = configs(executor.buildInvocation({ ...claim, agentKey, context: { agentKey: '../../escape' } }));
    assert.ok(args.some(value => value.startsWith('developer_instructions=')));
    assert.ok(args.every(value => !value.includes('../../escape')));
  }
  assert.throws(() => executor.buildInvocation({ ...claim, agentKey: '../../escape' }), /INVALID_RUNTIME_ROLE/);
});

test('production runner requires an explicit model before it can claim work', () => {
  const env = { MULINO_AUTH_ISSUER: 'https://tenant.example/', MULINO_WORKER_CLIENT_ID: 'worker',
    MULINO_WORKER_CLIENT_SECRET: 'worker-secret', MULINO_WORKER_ID: 'worker-1',
    MULINO_RUNTIME_IMAGE: config.image, MULINO_CODEX_AUTH_VOLUME: config.authVolume };
  assert.throws(() => readConfig(env), /MISSING_MODEL/);
  assert.equal(readConfig({ ...env, MULINO_CODEX_MODEL: 'demo-model' }).model, 'demo-model');
});
