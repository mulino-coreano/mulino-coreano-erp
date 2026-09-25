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
  const env = { MULINO_WORKER_TOKEN: 'worker-secret', MULINO_WORKER_ID: 'worker-1',
    MULINO_RUNTIME_IMAGE: config.image, MULINO_CODEX_AUTH_VOLUME: config.authVolume };
  assert.throws(() => readConfig(env), /MISSING_MODEL/);
  assert.equal(readConfig({ ...env, MULINO_CODEX_MODEL: 'demo-model' }).model, 'demo-model');
});

test('Claude runtime runs claude headless with the schema, the mulino CLI only and its own login volume', () => {
  const spec = new DockerExecutor({ ...config, runtime: 'CLAUDE' }).buildInvocation(claim);
  const arg = flag => spec.args[spec.args.indexOf(flag) + 1];
  assert.ok(spec.args.includes('--entrypoint=claude'));
  assert.ok(spec.args.includes('type=volume,src=mulino-runtime-auth-test,dst=/home/mulino/.claude'));
  assert.ok(spec.args.includes('CLAUDE_CONFIG_DIR=/home/mulino/.claude'));
  assert.equal(arg('--model'), 'demo-model');
  assert.equal(arg('--permission-mode'), 'dontAsk');
  assert.equal(arg('--allowedTools'), 'Bash(mulino:*)');
  assert.deepEqual(JSON.parse(arg('--json-schema')).required, ['outcome', 'summary', 'waitingConditions', 'resultRef']);
  assert.match(arg('--append-system-prompt'), /\/opt\/mulino\/skills\/supply-chain\/SKILL\.md/);
  assert.ok(!spec.args.some(value => value.startsWith('CODEX_HOME=')));
  assert.ok(spec.args.includes('--read-only') && spec.args.includes('--cap-drop=ALL'));
  assert.doesNotMatch(JSON.stringify(spec.args), /runtime-test-capability/);
});

test('runtime is configuration: unsupported values fail before any claim', () => {
  const env = { MULINO_WORKER_TOKEN: 'worker-secret', MULINO_WORKER_ID: 'worker-1', MULINO_RUNTIME_IMAGE: config.image,
    MULINO_AUTH_VOLUME: config.authVolume, MULINO_CODEX_MODEL: 'demo-model' };
  assert.equal(readConfig(env).runtime, 'CODEX');
  assert.equal(readConfig({ ...env, MULINO_AGENT_RUNTIME: 'CLAUDE' }).runtime, 'CLAUDE');
  assert.equal(readConfig({ ...env, MULINO_AGENT_MODEL: 'claude-sonnet-5' }).model, 'claude-sonnet-5');
  assert.throws(() => readConfig({ ...env, MULINO_AGENT_RUNTIME: 'GPT' }), /UNSUPPORTED_RUNTIME/);
  assert.throws(() => new DockerExecutor({ ...config, runtime: 'GPT' }), /INVALID_DOCKER_CONFIG/);
});
