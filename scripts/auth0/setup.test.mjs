import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, readFile, stat, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { runSetup } from './setup.mjs';

const baseEnv = {
  MULINO_AUTH_ISSUER: 'https://tenant.example/',
  MULINO_PUBLIC_ORIGIN: 'https://mcp.example',
  AUTH0_MANAGEMENT_TOKEN: 'fake-management-secret',
  MULINO_CHATGPT_CIMD_URL: 'https://chat.example/client.json',
  MULINO_CODEX_CIMD_URL: 'https://codex.example/client.json',
};

async function fixture(t) {
  const state = { settings: {}, resources: [], clients: [], grants: [], requests: [], failOnce: null, discoveryOverrides: {}, resourceOverrides: {}, cimdOverrides: {}, ignoreObo: false };
  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    let data = '';
    for await (const chunk of req) data += chunk;
    const body = data ? JSON.parse(data) : null;
    state.requests.push({ method: req.method, path: url.pathname, query: url.searchParams, body, authorization: req.headers.authorization });
    res.setHeader('content-type', 'application/json');
    const send = (status, value) => { res.statusCode = status; res.end(JSON.stringify(value)); };
    if (state.failOnce === `${req.method} ${url.pathname}`) {
      state.failOnce = null;
      return send(403, { error: 'fake-management-secret fake-generated-secret' });
    }
    if (url.pathname === '/.well-known/openid-configuration') return send(200, {
      issuer: 'https://tenant.example/', authorization_endpoint: 'https://tenant.example/authorize',
      token_endpoint: 'https://tenant.example/oauth/token', jwks_uri: 'https://tenant.example/.well-known/jwks.json',
      response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      authorization_response_iss_parameter_supported: state.settings.authorization_response_iss_parameter_supported === true,
      client_id_metadata_document_supported: state.settings.client_id_metadata_document_supported === true,
      ...state.discoveryOverrides,
    });
    if (url.pathname === '/.well-known/oauth-protected-resource/mcp') return send(200, {
      resource: 'https://mcp.example/mcp', authorization_servers: ['https://tenant.example/'],
      scopes_supported: ['erp:read', 'work:write', 'offline_access'], bearer_methods_supported: ['header'],
      ...state.resourceOverrides,
    });
    if (url.pathname.startsWith('/cimd/')) return send(200, {
      client_id: `https://${url.pathname.split('/')[2]}.example/client.json`,
      client_name: 'Example MCP client', application_type: 'web',
      redirect_uris: ['https://client.example/callback'], grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'], token_endpoint_auth_method: 'none',
      ...state.cimdOverrides,
    });
    if (req.headers.authorization !== 'Bearer fake-management-secret') return send(401, {});
    if (url.pathname === '/api/v2/tenants/settings') {
      if (req.method === 'PATCH') Object.assign(state.settings, body);
      return send(200, state.settings);
    }
    if (url.pathname === '/api/v2/clients/cimd/register') {
      const client = { client_id: `cimd-${state.clients.length}`, external_client_id: body.external_client_id,
        external_metadata_type: 'cimd', name: 'Example MCP client', app_type: 'regular_web',
        callbacks: ['https://client.example/callback'], token_endpoint_auth_method: 'none',
        grant_types: state.missingRegisteredRefresh ? ['authorization_code'] : ['authorization_code', 'refresh_token'] };
      state.clients.push(client);
      return send(201, { client_id: client.client_id, mapped_fields: {
        external_client_id: client.external_client_id, name: client.name, app_type: client.app_type,
        callbacks: client.callbacks, token_endpoint_auth_method: client.token_endpoint_auth_method, grant_types: client.grant_types,
      } });
    }
    for (const [path, key, idKey] of [['resource-servers', 'resources', 'id'], ['clients', 'clients', 'client_id'], ['client-grants', 'grants', 'id']]) {
      if (url.pathname === `/api/v2/${path}`) {
        if (req.method === 'GET') {
          let values = state[key];
          if (url.searchParams.has('external_client_id')) values = values.filter(v => v.external_client_id === url.searchParams.get('external_client_id'));
          const page = Number(url.searchParams.get('page') ?? 0);
          const perPage = Number(url.searchParams.get('per_page') ?? 100);
          const items = values.slice(page * perPage, (page + 1) * perPage);
          return send(200, items);
        }
        const value = { ...body, [idKey]: `${key}-${state[key].length}` };
        if (state.ignoreObo && body.app_type === 'resource_server') delete value.token_exchange;
        if (key === 'clients') value.client_secret = 'fake-generated-secret';
        state[key].push(value);
        if (key === 'clients' && state.loseClientResponseOnce) {
          state.loseClientResponseOnce = false;
          return send(502, { error: 'fake-generated-secret' });
        }
        return send(201, value);
      }
      if (url.pathname.startsWith(`/api/v2/${path}/`)) {
        const id = decodeURIComponent(url.pathname.split('/').at(-1));
        const value = state[key].find(item => item[idKey] === id);
        if (!value) return send(404, {});
        if (req.method === 'PATCH') Object.assign(value, body);
        return send(200, value);
      }
    }
    return send(404, {});
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const fetchImpl = (input, init) => {
    const url = new URL(input);
    const path = url.hostname === 'chat.example' || url.hostname === 'codex.example'
      ? `/cimd/${url.hostname.split('.')[0]}/client.json` : url.pathname + url.search;
    return fetch(origin + path, init);
  };
  const directory = await mkdtemp(join(tmpdir(), 'mulino-auth0-test-'));
  t.after(async () => { await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true, force: true }); });
  const output = [];
  return { state, directory, options: { fetchImpl, log: value => output.push(value) }, output };
}

test('default inspection only reads and never requests or prints client secrets', async t => {
  const f = await fixture(t);
  f.state.clients.push({ client_id: 'unrelated', name: 'other', client_secret: 'unrelated-secret' });
  const result = await runSetup(baseEnv, f.options);
  assert.equal(result.mode, 'dry-run');
  assert.ok(result.actions.some(a => a.operation === 'create'));
  assert.ok(f.state.requests.every(r => r.method === 'GET'));
  const clientRead = f.state.requests.find(r => r.path === '/api/v2/clients');
  assert.ok(clientRead.query.get('fields'));
  assert.ok(!clientRead.query.get('fields').includes('client_secret'));
  assert.ok(f.state.requests.filter(r => !r.path.startsWith('/api/')).every(r => !r.authorization));
  assert.doesNotMatch(JSON.stringify([result, f.output]), /fake-management-secret|unrelated-secret/);
});

test('apply creates separate OBO and worker identities with least-privilege grants then reruns without mutations', async t => {
  const f = await fixture(t);
  const options = { ...f.options, apply: true, credentialsDir: f.directory };
  const result = await runSetup(baseEnv, options);
  assert.equal(result.ready, true);
  assert.equal(f.state.resources.length, 2);
  assert.equal(f.state.resources.find(r => r.identifier === 'https://mcp.example/mcp').allow_offline_access, true);
  assert.equal(f.state.clients.length, 4);
  const obo = f.state.clients.find(c => c.app_type === 'resource_server');
  assert.equal(obo.resource_server_identifier, 'https://mcp.example/mcp');
  assert.deepEqual(obo.token_exchange, { allow_any_profile_of_type: ['on_behalf_of_token_exchange'] });
  const worker = f.state.clients.find(c => c.app_type === 'non_interactive');
  assert.deepEqual(worker.grant_types, ['client_credentials']);
  assert.deepEqual(f.state.grants.find(g => g.client_id === worker.client_id).scope, ['worker:dispatch']);
  assert.equal(f.state.grants.find(g => g.client_id === obo.client_id).subject_type, 'user');
  assert.equal(f.state.grants.find(g => g.client_id === obo.client_id).audience, 'urn:mulino:erp-api');
  assert.ok(f.state.grants.filter(g => g.subject_type === 'user').every(g => !g.scope.includes('worker:dispatch')));
  for (const name of ['mcp', 'worker']) {
    const path = join(f.directory, `${name}.credentials.json`);
    assert.equal((await stat(path)).mode & 0o777, 0o600);
    assert.match(await readFile(path, 'utf8'), /fake-generated-secret/);
  }
  assert.doesNotMatch(JSON.stringify([result, f.output]), /fake-management-secret|fake-generated-secret/);
  f.state.requests.length = 0;
  await runSetup(baseEnv, options);
  assert.ok(f.state.requests.every(r => r.method === 'GET'));
  assert.ok(f.state.requests.every(r => !r.query.get('fields')?.includes('client_secret')));
});

test('failed apply keeps completed objects and rerun resumes without duplicates or leaked provider errors', async t => {
  const f = await fixture(t);
  f.state.failOnce = 'POST /api/v2/client-grants';
  const options = { ...f.options, apply: true, credentialsDir: f.directory };
  await assert.rejects(runSetup(baseEnv, options), error => {
    assert.match(error.message, /403/);
    assert.doesNotMatch(error.message, /fake-management-secret|fake-generated-secret/);
    return true;
  });
  assert.equal(f.state.resources.length, 2);
  assert.equal(f.state.clients.filter(c => c.app_type === 'resource_server').length, 1);
  await runSetup(baseEnv, options);
  assert.equal(f.state.resources.length, 2);
  assert.equal(f.state.clients.length, 4);
  assert.equal(f.state.grants.length, 4);
});

test('occupied credential file rejects apply before any mutation', async t => {
  const f = await fixture(t);
  await writeFile(join(f.directory, 'mcp.credentials.json'), 'do-not-overwrite', { mode: 0o600 });
  await assert.rejects(runSetup(baseEnv, { ...f.options, apply: true, credentialsDir: f.directory }), /credential/i);
  assert.ok(f.state.requests.every(r => r.method === 'GET'));
  assert.equal(await readFile(join(f.directory, 'mcp.credentials.json'), 'utf8'), 'do-not-overwrite');
});

test('unsafe configuration is rejected before any request without reflecting supplied secrets', async t => {
  const f = await fixture(t);
  for (const override of [
    { MULINO_AUTH_ISSUER: 'https://name:fake-secret@tenant.example/' },
    { MULINO_PUBLIC_ORIGIN: 'http://public.example' },
    { MULINO_MCP_AUDIENCE: 'https://different.example/mcp' },
    { MULINO_API_AUDIENCE: 'https://mcp.example/mcp' },
  ]) {
    await assert.rejects(runSetup({ ...baseEnv, ...override }, f.options), error => {
      assert.doesNotMatch(error.message, /fake-secret/);
      return true;
    });
  }
  assert.equal(f.state.requests.length, 0);
});

test('missing tenant capabilities and missing real client CIMD URLs report not ready', async t => {
  const f = await fixture(t);
  const result = await runSetup({ ...baseEnv, MULINO_CHATGPT_CIMD_URL: '', MULINO_CODEX_CIMD_URL: '' }, f.options);
  assert.equal(result.ready, false);
  assert.ok(result.checks.some(c => c.name === 'CIMD support' && !c.ok));
  assert.ok(result.checks.some(c => c.name === 'Authorization response issuer' && !c.ok));
  assert.equal(result.manualSteps.length > 0, true);
});

test('offline plan needs no Management API token or network', async t => {
  const f = await fixture(t);
  const result = await runSetup({ ...baseEnv, AUTH0_MANAGEMENT_TOKEN: '' }, { ...f.options, planOnly: true });
  assert.equal(result.mode, 'plan');
  assert.ok(result.actions.length >= 6);
  assert.equal(f.state.requests.length, 0);
});

test('apply refuses wrong issuer, missing PKCE and unsafe CIMD before writing tenant configuration', async t => {
  const f = await fixture(t);
  for (const changes of [
    { discoveryOverrides: { issuer: 'https://wrong.example/' } },
    { discoveryOverrides: { code_challenge_methods_supported: ['plain'] } },
    { discoveryOverrides: { grant_types_supported: ['client_credentials'] } },
    { cimdOverrides: { redirect_uris: ['http://remote.example/callback'] } },
  ]) {
    Object.assign(f.state, { discoveryOverrides: {}, cimdOverrides: {}, ...changes });
    await assert.rejects(runSetup(baseEnv, { ...f.options, apply: true, credentialsDir: f.directory }), /preflight/i);
  }
  assert.ok(f.state.requests.every(r => r.method === 'GET'));
});

test('wrong public resource metadata prevents readiness without exposing its content', async t => {
  const f = await fixture(t);
  f.state.resourceOverrides = { resource: 'https://wrong.example/mcp', message: 'fake-provider-secret' };
  const result = await runSetup(baseEnv, { ...f.options, apply: true, credentialsDir: f.directory });
  assert.equal(result.ready, false);
  assert.ok(result.checks.some(c => c.name === 'MCP protected resource' && !c.ok));
  assert.doesNotMatch(JSON.stringify(result), /fake-provider-secret/);
});

test('pagination locates an existing OBO client after the first page', async t => {
  const f = await fixture(t);
  f.state.clients = Array.from({ length: 100 }, (_, i) => ({ client_id: `unrelated-${i}`, name: `unrelated-${i}` }));
  f.state.clients.push({ client_id: 'existing-obo', app_type: 'resource_server', resource_server_identifier: 'https://mcp.example/mcp', client_secret: 'fake-generated-secret' });
  await runSetup(baseEnv, { ...f.options, apply: true, credentialsDir: f.directory });
  assert.equal(f.state.clients.filter(c => c.app_type === 'resource_server').length, 1);
  assert.ok(f.state.requests.some(r => r.path === '/api/v2/clients' && r.query.get('page') === '1'));
});

test('provider array ordering does not cause unnecessary grant or client mutations', async t => {
  const f = await fixture(t);
  const options = { ...f.options, apply: true, credentialsDir: f.directory };
  await runSetup(baseEnv, options);
  for (const grant of f.state.grants) grant.scope.reverse();
  for (const resource of f.state.resources) resource.scopes.reverse();
  f.state.requests.length = 0;
  await runSetup(baseEnv, options);
  assert.ok(f.state.requests.every(r => r.method === 'GET'));
});

test('provider silently omitting OBO settings cannot report configuration ready', async t => {
  const f = await fixture(t);
  f.state.ignoreObo = true;
  const result = await runSetup(baseEnv, { ...f.options, apply: true, credentialsDir: f.directory });
  assert.equal(result.ready, false);
  assert.ok(result.checks.some(c => c.name === 'Tenant objects configured' && !c.ok));
});

test('lost create response is recovered through existing client lookup and explicit credential export', async t => {
  const f = await fixture(t);
  f.state.loseClientResponseOnce = true;
  const options = { ...f.options, apply: true, credentialsDir: f.directory };
  await assert.rejects(runSetup(baseEnv, options), /502/);
  assert.equal(f.state.clients.length, 1);
  const result = await runSetup(baseEnv, options);
  assert.equal(result.ready, true);
  assert.equal(f.state.clients.filter(c => c.app_type === 'resource_server').length, 1);
  assert.equal(JSON.parse(await readFile(join(f.directory, 'mcp.credentials.json'), 'utf8')).client_id, f.state.clients[0].client_id);
});

test('CLI plan and invalid argument output never reflect the Management API token or argument value', () => {
  const script = new URL('./setup.mjs', import.meta.url).pathname;
  const plan = spawnSync(process.execPath, [script, '--plan'], { env: baseEnv, encoding: 'utf8' });
  assert.equal(plan.status, 0);
  assert.equal(JSON.parse(plan.stdout).mode, 'plan');
  assert.doesNotMatch(plan.stdout + plan.stderr, /fake-management-secret/);
  const invalid = spawnSync(process.execPath, [script, '--fake-secret-value'], { env: baseEnv, encoding: 'utf8' });
  assert.equal(invalid.status, 1);
  assert.doesNotMatch(invalid.stdout + invalid.stderr, /fake-management-secret|fake-secret-value/);
});

test('offline access disabled on an existing MCP API is planned and repaired idempotently', async t => {
  const f = await fixture(t);
  const options = { ...f.options, apply: true, credentialsDir: f.directory };
  await runSetup(baseEnv, options);
  f.state.resources.find(r => r.identifier === 'https://mcp.example/mcp').allow_offline_access = false;
  f.state.requests.length = 0;
  const inspected = await runSetup(baseEnv, f.options);
  assert.equal(inspected.ready, false);
  assert.ok(inspected.actions.some(a => a.target === 'Mulino MCP' && a.operation === 'update'));
  assert.ok(f.state.requests.every(r => r.method === 'GET'));
  const repaired = await runSetup(baseEnv, options);
  assert.equal(repaired.ready, true);
  assert.ok(f.state.requests.some(r => r.method === 'PATCH' && r.path.startsWith('/api/v2/resource-servers/')
    && r.body.allow_offline_access === true));
  f.state.requests.length = 0;
  await runSetup(baseEnv, options);
  assert.ok(f.state.requests.every(r => r.method === 'GET'));
});

test('CIMD without refresh_token remains incomplete with actionable manual guidance', async t => {
  const f = await fixture(t);
  f.state.cimdOverrides = { grant_types: ['authorization_code'] };
  const result = await runSetup(baseEnv, { ...f.options, apply: true, credentialsDir: f.directory });
  assert.equal(result.ready, false);
  assert.ok(result.checks.some(c => c.name === 'CHATGPT CIMD refresh token' && !c.ok));
  assert.ok(result.manualSteps.some(step => step.includes('refresh_token') && step.includes('grant_types')));
});

test('persisted CIMD client missing the refresh grant remains incomplete even when its hosted document supports it', async t => {
  const f = await fixture(t);
  f.state.missingRegisteredRefresh = true;
  const result = await runSetup(baseEnv, { ...f.options, apply: true, credentialsDir: f.directory });
  assert.equal(result.ready, false);
  assert.ok(result.checks.some(c => c.name === 'CHATGPT registered CIMD refresh token' && !c.ok));
  assert.ok(result.manualSteps.some(step => step.includes('Auth0') && step.includes('grant_types')));
});

test('missing public offline_access scope or authorization server refresh support prevents readiness', async t => {
  const f = await fixture(t);
  const options = { ...f.options, apply: true, credentialsDir: f.directory };
  await runSetup(baseEnv, options);
  f.state.resourceOverrides = { scopes_supported: ['erp:read', 'work:write'] };
  const missingScope = await runSetup(baseEnv, f.options);
  assert.equal(missingScope.ready, false);
  assert.ok(missingScope.checks.some(c => c.name === 'MCP protected resource' && !c.ok));
  f.state.resourceOverrides = {};
  f.state.discoveryOverrides = { grant_types_supported: ['authorization_code'] };
  const missingGrant = await runSetup(baseEnv, f.options);
  assert.equal(missingGrant.ready, false);
  assert.ok(missingGrant.checks.some(c => c.name === 'Refresh token grant' && !c.ok));
});
