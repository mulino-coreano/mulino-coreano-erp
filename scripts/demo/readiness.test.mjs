import test from 'node:test';
import assert from 'node:assert/strict';
import { runReadiness, dbTarget, command } from './readiness.mjs';
const env = {
  DB_URL: 'jdbc:postgresql://127.0.0.1:5432/demo_only', DB_USERNAME: 'fixture_user', DB_PASSWORD: 'secret-password',
  MULINO_API_BASE: 'http://127.0.0.1:8080/api/v1', MULINO_DEMO_HUMAN_API_TOKEN: 'secret-human-token',
  MULINO_AUTH_ISSUER: 'https://issuer.example/', MULINO_PUBLIC_ORIGIN: 'https://mcp.example',
  MULINO_AUTH0_CLIENT_ID: 'obo-client', MULINO_AUTH0_CLIENT_SECRET: 'secret-obo',
  MULINO_WORKER_CLIENT_ID: 'worker-client', MULINO_WORKER_CLIENT_SECRET: 'secret-worker', MULINO_WORKER_ID: 'fixture-worker',
  MULINO_AGENT_API_URL: 'http://host.docker.internal:8080/api/v1', MULINO_RUNTIME_IMAGE: 'mulino:test',
  MULINO_CODEX_AUTH_VOLUME: 'dedicated-demo-auth', MULINO_CODEX_MODEL: 'fixture-model',
  MULINO_CHATGPT_CIMD_URL: 'https://chatgpt.example/client', MULINO_CODEX_CIMD_URL: 'https://codex.example/client',
};
function fixture(overrides = {}) {
  const calls = [];
  return { calls, deps: {
    nodeVersion: '22.1.0', timeoutMs: 100, access: async () => {}, readdir: async () => ['V1__base.sql', 'V25__followup.sql'],
    command: async (file, args, options) => {
      calls.push({ file, args, options });
      if (file === 'java') return 'openjdk version "21.0.9"';
      if (file === 'zig') return '0.16.0';
      if (file === 'psql') return '180001\n25\n0';
      return 'fixture';
    },
    fetch: async (url, options) => {
      calls.push({ url, options });
      let body;
      if (url.endsWith('/me')) body = { actorType: 'HUMAN', role: 'MANAGER', capabilities: ['erp:read', 'procurement:decide'] };
      else if (url.includes('openid-configuration')) body = { issuer: env.MULINO_AUTH_ISSUER,
        response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'],
        code_challenge_methods_supported: ['S256'], client_id_metadata_document_supported: true,
        authorization_response_iss_parameter_supported: true, authorization_endpoint: 'https://issuer.example/authorize',
        token_endpoint: 'https://issuer.example/token', jwks_uri: 'https://issuer.example/jwks' };
      else if (url.includes('oauth-protected-resource')) body = { resource: 'https://mcp.example/mcp',
        authorization_servers: [env.MULINO_AUTH_ISSUER], scopes_supported: ['erp:read', 'work:write', 'procurement:decide', 'offline_access'],
        bearer_methods_supported: ['header'] };
      else body = { client_id: url, grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'],
        token_endpoint_auth_method: 'none', redirect_uris: ['http://127.0.0.1:3210/callback'] };
      return Response.json(body);
    }, ...overrides,
  } };
}
const status = (report, name) => report.checks.find(check => check.component === name).status;
test('complete configuration is only software PASS, never live acceptance; checks never mutate', async () => {
  const { calls, deps } = fixture();
  const report = await runReadiness(env, deps);
  assert.equal(report.softwareConfigurationChecksPassed, true);
  assert.equal(report.ready, false);
  assert.equal(status(report, 'codex-volume-login'), 'UNVERIFIED');
  for (const call of calls) {
    if (call.url) {
      assert.equal(call.options.method, 'GET'); assert.equal(call.options.redirect, 'error');
      assert.equal(Boolean(call.options.headers.Authorization), call.url.endsWith('/me'));
    } else if (call.file === 'docker') assert(['version', 'image', 'volume'].includes(call.args[0]));
  }
  const probe = calls.find(call => call.file === 'psql');
  assert(probe.args.includes('-X') && probe.args.includes('-w'));
  assert.match(probe.args.at(-1), /^BEGIN READ ONLY; SELECT/);
  assert.match(probe.args.at(-1), /ROLLBACK;$/);
  assert.equal(probe.options.env.PGPASSWORD, env.DB_PASSWORD);
  assert(!JSON.stringify(probe.args).includes(env.DB_PASSWORD));
  assert(!JSON.stringify(report).includes('secret-'));
});
test('missing configuration never contacts network or implicit local DB', async () => {
  const { calls, deps } = fixture();
  const report = await runReadiness({}, deps);
  assert.equal(status(report, 'database'), 'MISSING');
  assert.equal(status(report, 'model-selection'), 'MISSING');
  assert(!calls.some(call => call.url || call.file === 'psql'));
});
for (const bad of ['postgresql:///demo', 'postgresql://user:secret@host/demo', 'postgresql://host/demo?options=-cfoo',
  'postgresql://host/demo#secret', 'postgresql://host/demo/extra', 'file:///tmp/demo', 'postgresql://host/%2Ftmp']) {
  test(`reject unsafe DB target ${bad.split(':')[0]} variant`, () => assert.throws(() => dbTarget({ ...env, DB_URL: bad })));
}
test('remote PostgreSQL requires verified TLS and isolated libpq startup options', () => {
  const target = dbTarget({ ...env, DB_URL: 'postgresql://db.example/demo' });
  assert.equal(target.PGSSLMODE, 'verify-full');
  assert.match(target.PGOPTIONS, /default_transaction_read_only=on/);
});
test('PG major or Flyway mismatch fails', async () => {
  for (const output of ['170006\n25\n0', '180001\n24\n0', '180001\n26\n0', '180001\n25\n1']) {
    const base = fixture();
    const report = await runReadiness(env, { ...base.deps, command: (file, ...rest) => file === 'psql' ? output : base.deps.command(file, ...rest) });
    assert.equal(status(report, 'database'), 'FAILED');
  }
});
test('unsafe public URL and credentialed API URL cannot send tokens', async () => {
  const { calls, deps } = fixture();
  const report = await runReadiness({ ...env, MULINO_PUBLIC_ORIGIN: 'http://mcp.example', MULINO_API_BASE: 'https://user:secret@api.example/api/v1' }, deps);
  assert.equal(status(report, 'mcp-metadata'), 'FAILED');
  assert.equal(status(report, 'backend-human'), 'FAILED');
  assert(!calls.some(call => call.url?.includes('/me') || call.url?.includes('oauth-protected-resource')));
});
test('metadata must advertise procurement decision scope', async () => {
  const base = fixture();
  const report = await runReadiness(env, { ...base.deps, fetch: async (url, options) => {
    const response = await base.deps.fetch(url, options);
    const body = await response.json();
    if (body.scopes_supported) body.scopes_supported = ['erp:read', 'work:write', 'offline_access'];
    return Response.json(body);
  } });
  assert.equal(status(report, 'mcp-metadata'), 'FAILED');
});
test('provider errors and command errors never leak bodies, tokens, DSNs or env values', async () => {
  const leak = Object.values(env).join(' ');
  const { deps } = fixture({ command: async () => { throw new Error(leak); }, fetch: async () => new Response(leak, { status: 401 }) });
  const report = await runReadiness(env, deps);
  const text = JSON.stringify(report);
  for (const value of [env.DB_URL, env.DB_PASSWORD, env.MULINO_DEMO_HUMAN_API_TOKEN, env.MULINO_WORKER_CLIENT_SECRET]) assert(!text.includes(value));
  assert.equal(status(report, 'backend-human'), 'FAILED');
});
test('stalled command and stalled HTTP body are bounded', async () => {
  const base = fixture({ timeoutMs: 15 });
  const started = Date.now();
  const report = await runReadiness({ ...env, DB_URL: '' }, { ...base.deps,
    command: async () => new Promise(() => {}),
    fetch: async () => new Response(new ReadableStream({ start() {} })),
  });
  assert(Date.now() - started < 1500);
  assert.equal(status(report, 'issuer-discovery'), 'FAILED');
  assert.equal(status(report, 'java'), 'FAILED');
});
test('native command timeout kills a child and returns a fixed error', async () => {
  await assert.rejects(command(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { timeoutMs: 25 }), /COMMAND_FAILED/);
});
test('missing executable is MISSING; incompatible runtimes fail', async () => {
  const { deps } = fixture({ nodeVersion: '20.0.0', command: async file => {
    if (file === 'java') throw new Error('MISSING_COMMAND');
    if (file === 'zig') return '0.15.2';
    return 'fixture';
  } });
  const report = await runReadiness({}, deps);
  assert.equal(status(report, 'java'), 'MISSING');
  assert.equal(status(report, 'node'), 'FAILED');
  assert.equal(status(report, 'zig'), 'FAILED');
});
test('redirected response, service identity and oversized metadata fail', async () => {
  for (const response of [() => new Response('secret', { status: 302, headers: { Location: 'https://evil.example/' } }),
    () => Response.json({ actorType: 'SERVICE', role: 'MANAGER', capabilities: ['erp:read', 'procurement:decide'] }),
    () => new Response('x'.repeat(262145))]) {
    const { deps } = fixture({ fetch: async () => response() });
    assert.equal(status(await runReadiness(env, deps), 'backend-human'), 'FAILED');
  }
});

test('noncanonical issuer fails the deployed MCP config contract without issuer or resource probes', async () => {
  for (const issuer of ['https://issuer.example', 'https://ISSUER.example/', 'https://issuer.example:443/']) {
    const { calls, deps } = fixture();
    const report = await runReadiness({ ...env, MULINO_AUTH_ISSUER: issuer }, deps);
    assert.equal(status(report, 'mcp-config'), 'FAILED');
    assert.equal(status(report, 'issuer-discovery'), 'FAILED');
    assert.equal(status(report, 'mcp-metadata'), 'FAILED');
    assert.equal(report.softwareConfigurationChecksPassed, false);
    assert(!calls.some(call => call.url?.includes('openid-configuration') || call.url?.includes('oauth-protected-resource')));
  }
});
test('MCP configuration checks use the actual startup validator', async () => {
  const { deps } = fixture();
  const report = await runReadiness({ ...env, MULINO_PORT: '65536' }, deps);
  assert.equal(status(report, 'mcp-config'), 'FAILED');
});
