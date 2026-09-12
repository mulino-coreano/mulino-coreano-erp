#!/usr/bin/env node
import { constants } from 'node:fs';
import { lstat, mkdir, open, readFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const HUMAN_SCOPES = ['erp:read', 'work:write', 'procurement:decide'];
// Deployed human conversation tools require read, work and purchase-decision scopes.
const DEPLOYED_MCP_SCOPES = [...HUMAN_SCOPES, 'offline_access'];
const TENANT_SETTINGS = {
  resource_parameter_profile: 'compatibility',
  client_id_metadata_document_supported: true,
  authorization_response_iss_parameter_supported: true,
};
const CLIENT_FIELDS = 'client_id,name,app_type,client_metadata,resource_server_identifier,token_exchange,oidc_conformant,grant_types,token_endpoint_auth_method,external_client_id,external_metadata_type';
const MANUAL_STEPS = [
  'Auth0 로그인 연결을 CIMD 앱에 허용하고 실제 사용자에게 두 API의 필요한 권한을 배정한다. ERP 역할은 별도 사전 연결한다.',
  'ChatGPT와 Codex 각각에서 OAuth 로그인과 OBO 호출을 실행해 issuer, audience, scope와 사용자 subject 보존을 확인한다.',
  '갱신 검사 실패 시 각 CIMD 문서와 Auth0 등록 client의 grant_types에 refresh_token이 있는지 확인한다. 제공자 문서가 갱신을 지원하면 Auth0에서 CIMD를 검토·동기화하고, MCP API의 Allow Offline Access와 공개 offline_access 광고를 확인한 뒤 재연결하여 실제 토큰 갱신을 시험한다.',
  'ChatGPT와 Codex에서 결정 도구의 매번 확인 UX를 시험한다. 이 스크립트는 로그인·OBO·승인 UX의 성공을 증명하지 않는다.',
];

// Only fixed messages cross the CLI error boundary; provider error bodies and causes never do.
class SetupError extends Error {}

function httpsUrl(value, label, originOnly = false) {
  let url;
  try { url = new URL(value); } catch { throw new SetupError(`${label}: HTTPS URL 설정이 필요합니다.`); }
  if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.search
    || (originOnly && url.pathname !== '/')) {
    throw new SetupError(`${label}: 인증정보·query·fragment가 없는 HTTPS ${originOnly ? 'origin' : 'URL'}이 필요합니다.`);
  }
  return url;
}

function configFrom(env) {
  const issuerUrl = httpsUrl(env.MULINO_AUTH_ISSUER, 'MULINO_AUTH_ISSUER', true);
  const origin = httpsUrl(env.MULINO_PUBLIC_ORIGIN, 'MULINO_PUBLIC_ORIGIN', true).origin;
  const issuer = issuerUrl.href;
  const mcpAudience = env.MULINO_MCP_AUDIENCE || `${origin}/mcp`;
  if (mcpAudience !== `${origin}/mcp`) throw new SetupError('MULINO_MCP_AUDIENCE는 MULINO_PUBLIC_ORIGIN/mcp와 같아야 합니다.');
  const apiAudience = env.MULINO_API_AUDIENCE || 'urn:mulino:erp-api';
  if (apiAudience !== 'urn:mulino:erp-api') throw new SetupError('MULINO_API_AUDIENCE는 urn:mulino:erp-api여야 합니다.');
  const managementOrigin = httpsUrl(env.AUTH0_MANAGEMENT_ORIGIN || issuer, 'AUTH0_MANAGEMENT_ORIGIN', true).origin;
  const cimd = ['CHATGPT', 'CODEX'].map(name => ({
    name,
    url: env[`MULINO_${name}_CIMD_URL`] ? httpsUrl(env[`MULINO_${name}_CIMD_URL`], `MULINO_${name}_CIMD_URL`).href : null,
  }));
  return { issuer, origin, mcpAudience, apiAudience, managementOrigin, cimd };
}

function specifications(config) {
  const scopeObjects = values => values.map(value => ({ value, description: value }));
  const resource = (identifier, name, scopes) => ({
    identifier, name, signing_alg: 'RS256', token_dialect: 'rfc9068_profile_authz', enforce_policies: true,
    scopes: scopeObjects(scopes), subject_type_authorization: {
      user: { policy: 'require_client_grant' }, client: { policy: 'require_client_grant' },
    }, skip_consent_for_verifiable_first_party_clients: false,
  });
  const metadata = role => ({ mulino_setup: 'v1', mulino_role: role, mulino_resource: config.mcpAudience });
  return {
    resources: [
      { ...resource(config.mcpAudience, 'Mulino MCP', HUMAN_SCOPES), allow_offline_access: true },
      resource(config.apiAudience, 'Mulino ERP API', [...HUMAN_SCOPES, 'worker:dispatch']),
    ],
    clients: [
      { role: 'mcp', body: {
        name: `Mulino MCP OBO (${new URL(config.origin).hostname})`, app_type: 'resource_server',
        resource_server_identifier: config.mcpAudience, oidc_conformant: true,
        token_endpoint_auth_method: 'client_secret_post',
        token_exchange: { allow_any_profile_of_type: ['on_behalf_of_token_exchange'] },
        client_metadata: metadata('mcp'),
      } },
      { role: 'worker', body: {
        name: `Mulino ERP worker (${new URL(config.origin).hostname})`, app_type: 'non_interactive',
        oidc_conformant: true, grant_types: ['client_credentials'], token_endpoint_auth_method: 'client_secret_post',
        client_metadata: metadata('worker'),
      } },
    ],
  };
}

// Auth0 may return scope/grant arrays in a different order; all arrays managed here are sets.
function comparable(value) {
  if (Array.isArray(value)) return value.map(comparable).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b)));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, comparable(item)]));
  return value;
}

function difference(current, desired) {
  const patch = {};
  for (const [key, value] of Object.entries(desired)) {
    if (!isDeepStrictEqual(comparable(current?.[key]), comparable(value))) patch[key] = value;
  }
  return patch;
}

function unique(values, predicate, label) {
  const matches = values.filter(predicate);
  if (matches.length > 1) throw new SetupError(`${label}: 중복 객체가 있어 자동 선택할 수 없습니다.`);
  return matches[0];
}

function clientMatch(clients, spec, config) {
  const current = unique(clients, client => spec.role === 'mcp'
    ? client.resource_server_identifier === config.mcpAudience
    : client.client_metadata?.mulino_role === 'worker' && client.client_metadata?.mulino_resource === config.mcpAudience,
  `${spec.role} client`);
  if (current && current.app_type !== spec.body.app_type) throw new SetupError(`${spec.role} client: app_type 충돌입니다.`);
  if (!current && clients.some(client => client.name === spec.body.name)) throw new SetupError(`${spec.role} client: 기존 이름과 충돌합니다. 기존 객체를 먼저 검토하세요.`);
  return current;
}

function requester(fetchImpl, config, token) {
  async function json(url, { method = 'GET', body, management = false } = {}) {
    let response;
    try {
      response = await fetchImpl(url, {
        method, redirect: 'error', signal: AbortSignal.timeout(15000),
        headers: { accept: 'application/json', ...(body ? { 'content-type': 'application/json' } : {}),
          ...(management ? { authorization: `Bearer ${token}` } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}),
      });
    } catch { throw new SetupError(`${management ? 'Management API' : 'Metadata'} ${method}: 연결 실패 또는 redirect 거부.`); }
    if (!response.ok) throw new SetupError(`${management ? 'Management API' : 'Metadata'} ${method}: HTTP ${response.status}. 응답 본문은 숨겼습니다.`);
    try { return await response.json(); } catch { throw new SetupError('API 응답이 유효한 JSON이 아닙니다.'); }
  }
  const management = (path, method = 'GET', body) => json(`${config.managementOrigin}/api/v2/${path}`, { method, body, management: true });
  async function list(path, fields) {
    const values = [];
    for (let page = 0; page < 100; page++) {
      const params = new URLSearchParams({ page: String(page), per_page: '100', include_totals: 'false' });
      if (fields) { params.set('fields', fields); params.set('include_fields', 'true'); }
      const items = await management(`${path}?${params}`);
      if (!Array.isArray(items)) throw new SetupError('Management API 목록 응답 형식 오류입니다.');
      values.push(...items);
      if (items.length < 100) return values;
    }
    throw new SetupError('Management API 페이지 제한에 도달했습니다. 범위를 먼저 검토하세요.');
  }
  return { json, management, list };
}

async function preflight(config, request) {
  const checks = [];
  const check = (name, ok) => checks.push({ name, ok: Boolean(ok) });
  try {
    const metadata = await request.json(`${config.issuer}.well-known/openid-configuration`);
    check('Issuer discovery', metadata.issuer === config.issuer);
    check('Authorization Code + PKCE S256', metadata.response_types_supported?.includes('code')
      && (!metadata.grant_types_supported || metadata.grant_types_supported.includes('authorization_code'))
      && metadata.code_challenge_methods_supported?.includes('S256'));
    check('Refresh token grant', metadata.grant_types_supported?.includes('refresh_token'));
    check('CIMD support', metadata.client_id_metadata_document_supported === true);
    check('Authorization response issuer', metadata.authorization_response_iss_parameter_supported === true);
    for (const name of ['authorization_endpoint', 'token_endpoint', 'jwks_uri']) {
      try { check(name, httpsUrl(metadata[name], name).origin === new URL(config.issuer).origin); }
      catch { check(name, false); }
    }
  } catch { check('Issuer discovery', false); }
  try {
    const metadata = await request.json(`${config.origin}/.well-known/oauth-protected-resource/mcp`);
    check('MCP protected resource', metadata.resource === config.mcpAudience
      && metadata.authorization_servers?.includes(config.issuer)
      && DEPLOYED_MCP_SCOPES.every(scope => metadata.scopes_supported?.includes(scope))
      && metadata.bearer_methods_supported?.includes('header'));
  } catch { check('MCP protected resource', false); }
  for (const item of config.cimd) {
    if (!item.url) { check(`${item.name} CIMD document`, false); continue; }
    try {
      const document = await request.json(item.url);
      check(`${item.name} CIMD refresh token`, document.grant_types?.includes('refresh_token'));
      const redirects = document.redirect_uris;
      check(`${item.name} CIMD document`, document.client_id === item.url
        && document.grant_types?.includes('authorization_code') && document.response_types?.includes('code')
        && ['none', 'private_key_jwt'].includes(document.token_endpoint_auth_method)
        && Array.isArray(redirects) && redirects.length > 0 && redirects.every(uri => {
          try {
            const u = new URL(uri);
            return !u.username && !u.password && !u.hash && (u.protocol === 'https:'
              || (u.protocol === 'http:' && ['127.0.0.1', '[::1]', 'localhost'].includes(u.hostname)));
          } catch { return false; }
        }));
    } catch { check(`${item.name} CIMD document`, false); }
  }
  return checks;
}

async function credentialSlots(directory, specs, clients, config) {
  if (!directory) throw new SetupError('--apply에는 --credentials-dir 경로가 필요합니다.');
  const dir = resolve(directory);
  try { await mkdir(dir, { recursive: true, mode: 0o700 }); } catch { throw new SetupError('credential 디렉터리를 만들 수 없습니다.'); }
  const slots = new Map();
  for (const spec of specs) {
    const current = clientMatch(clients, spec, config);
    const path = join(dir, `${spec.role}.credentials.json`);
    let info;
    try { info = await lstat(path); } catch (error) {
      if (error.code !== 'ENOENT') throw new SetupError('credential 파일 상태 확인 실패입니다.');
    }
    if (info) {
      if (!info.isFile() || (info.mode & 0o777) !== 0o600 || info.size > 65536 || !current) {
        throw new SetupError('기존 credential 파일을 덮어쓸 수 없습니다. 경로·권한·기존 client를 확인하세요.');
      }
      let saved;
      try { saved = JSON.parse(await readFile(path, 'utf8')); } catch { throw new SetupError('기존 credential 파일을 해석할 수 없습니다. 덮어쓰지 않습니다.'); }
      if (saved.issuer !== config.issuer || saved.client_id !== current.client_id || typeof saved.client_secret !== 'string' || !saved.client_secret) {
        throw new SetupError('기존 credential 파일과 Auth0 client가 일치하지 않습니다. 덮어쓰지 않습니다.');
      }
    }
    slots.set(spec.role, { path, exists: Boolean(info) });
  }
  return slots;
}

async function saveCredentials(slot, client, config, request, log) {
  if (slot.exists) return;
  let handle;
  try { handle = await open(slot.path, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW, 0o600); }
  catch { throw new SetupError('credential 파일을 독점 생성할 수 없습니다. 기존 파일은 덮어쓰지 않습니다.'); }
  try {
    const credentials = client.client_secret ? client : await request.management(`clients/${encodeURIComponent(client.client_id)}?fields=client_id,client_secret&include_fields=true`);
    if (credentials.client_id !== client.client_id || typeof credentials.client_secret !== 'string' || !credentials.client_secret) {
      throw new SetupError('client secret을 받지 못했습니다. credential 파일 복구 절차를 확인하세요.');
    }
    await handle.writeFile(JSON.stringify({ issuer: config.issuer, client_id: client.client_id, client_secret: credentials.client_secret }, null, 2) + '\n');
    await handle.sync();
    log(`자격증명 저장 경로: ${slot.path}`);
  } catch (error) {
    if (error instanceof SetupError) throw error;
    throw new SetupError('credential 저장 실패입니다. 파일 복구 절차를 확인하세요.');
  } finally { await handle.close(); }
}

export async function runSetup(env = process.env, { apply = false, planOnly = false, credentialsDir, fetchImpl = fetch, log = () => {} } = {}) {
  const config = configFrom(env);
  const specs = specifications(config);
  if (apply && planOnly) throw new SetupError('--apply와 --plan은 함께 사용할 수 없습니다.');
  const actions = [];
  const configurationChecks = [];
  const manualSteps = [...MANUAL_STEPS];
  if (config.cimd.some(c => !c.url)) manualSteps.unshift('ChatGPT·Codex가 실제 제공하는 CIMD URL을 각각 설정한다. URL이나 callback을 추측해서 등록하지 않는다.');
  if (planOnly) return {
    mode: 'plan', ready: false, checks: [], manualSteps,
    actions: [
      { operation: 'ensure', target: 'tenant', body: TENANT_SETTINGS },
      ...specs.resources.map(body => ({ operation: 'ensure', target: 'resource-server', body })),
      ...specs.clients.map(spec => ({ operation: 'ensure', target: `${spec.role} client`, body: spec.body })),
      { operation: 'ensure', target: 'OBO user grant', audience: config.apiAudience, scope: HUMAN_SCOPES },
      { operation: 'ensure', target: 'worker client grant', audience: config.apiAudience, scope: ['worker:dispatch'] },
      ...config.cimd.filter(c => c.url).map(c => ({ operation: 'ensure', target: `${c.name} CIMD + MCP user grant`, url: c.url })),
    ],
  };
  const token = env.AUTH0_MANAGEMENT_TOKEN;
  if (apply && !token) throw new SetupError('--apply에는 AUTH0_MANAGEMENT_TOKEN이 필요합니다.');
  const request = requester(fetchImpl, config, token);
  if (apply) {
    const before = await preflight(config, request);
    const mandatory = ['Issuer discovery', 'Authorization Code + PKCE S256', 'authorization_endpoint', 'token_endpoint', 'jwks_uri',
      ...config.cimd.filter(c => c.url).map(c => `${c.name} CIMD document`)];
    if (mandatory.some(name => !before.find(c => c.name === name)?.ok)) {
      throw new SetupError('Auth0 preflight 실패: issuer·PKCE·OAuth endpoint·지정한 CIMD 문서를 확인하세요. 변경하지 않았습니다.');
    }
  }
  let settings;
  if (token) {
    settings = await request.management('tenants/settings');
    const resources = await request.list('resource-servers');
    const clients = await request.list('clients', CLIENT_FIELDS);
    const grants = await request.list('client-grants');
    // Validate identity and output conflicts before the first remote mutation.
    for (const spec of specs.clients) clientMatch(clients, spec, config);
    const slots = apply ? await credentialSlots(credentialsDir, specs.clients, clients, config) : null;
    async function ensure(path, current, desired, target, immutableKeys = []) {
      const patch = difference(current, desired);
      for (const key of immutableKeys) delete patch[key];
      const operation = current ? (Object.keys(patch).length ? 'update' : 'unchanged') : 'create';
      actions.push({ operation, target });
      if (!apply || operation === 'unchanged') return current || { ...desired, client_id: `planned-${target}` };
      return current ? request.management(path, 'PATCH', patch) : request.management(path, 'POST', desired);
    }
    const tenantPatch = difference(settings, TENANT_SETTINGS);
    actions.push({ operation: Object.keys(tenantPatch).length ? 'update' : 'unchanged', target: 'tenant' });
    if (apply && Object.keys(tenantPatch).length) settings = await request.management('tenants/settings', 'PATCH', tenantPatch);
    for (const desired of specs.resources) {
      const current = unique(resources, r => r.identifier === desired.identifier, 'resource-server');
      const body = { ...desired, scopes: [...(current?.scopes || []).filter(s => !desired.scopes.some(d => d.value === s.value)), ...desired.scopes] };
      await ensure(current ? `resource-servers/${encodeURIComponent(current.id)}` : 'resource-servers', current, body, desired.name, ['identifier']);
    }
    async function grant(client, audience, scope, subjectType, target) {
      const current = unique(grants, g => g.client_id === client.client_id && g.audience === audience
        && (g.subject_type || 'client') === subjectType, `${target} grant`);
      const desired = { client_id: client.client_id, audience, scope, subject_type: subjectType, allow_all_scopes: false };
      await ensure(current ? `client-grants/${encodeURIComponent(current.id)}` : 'client-grants', current, desired, `${target} grant`, ['client_id', 'audience', 'subject_type']);
    }
    for (const spec of specs.clients) {
      const current = clientMatch(clients, spec, config);
      const body = { ...spec.body, client_metadata: { ...current?.client_metadata, ...spec.body.client_metadata } };
      const client = await ensure(current ? `clients/${encodeURIComponent(current.client_id)}` : 'clients', current, body, `${spec.role} client`, ['app_type', 'resource_server_identifier']);
      if (apply) await saveCredentials(slots.get(spec.role), client, config, request, log);
      await grant(client, config.apiAudience, spec.role === 'mcp' ? HUMAN_SCOPES : ['worker:dispatch'], spec.role === 'mcp' ? 'user' : 'client', spec.role);
    }
    for (const item of config.cimd.filter(c => c.url)) {
      let client = unique(clients, c => c.external_client_id === item.url, `${item.name} CIMD`);
      actions.push({ operation: client ? 'unchanged' : 'create', target: `${item.name} CIMD` });
      if (!client) {
        client = apply ? await request.management('clients/cimd/register', 'POST', { external_client_id: item.url }) : { client_id: `planned-${item.name}` };
      }
      configurationChecks.push({ name: `${item.name} registered CIMD refresh token`,
        ok: Boolean((client.grant_types || client.mapped_fields?.grant_types)?.includes('refresh_token')) });
      await grant(client, config.mcpAudience, HUMAN_SCOPES, 'user', item.name);
    }
  } else manualSteps.unshift('AUTH0_MANAGEMENT_TOKEN이 없어 tenant/API/client/grant는 조회하지 않았다. 공개 metadata만 점검했다.');
  if (apply) {
    // A successful write response is insufficient: inspect persisted configuration and discovery again.
    const verified = await runSetup(env, { fetchImpl });
    return { ...verified, mode: 'apply', actions, remainingActions: verified.actions.filter(a => a.operation !== 'unchanged'), manualSteps };
  }
  const checks = [...await preflight(config, request), ...configurationChecks];
  checks.push({ name: 'Resource parameter compatibility', ok: settings?.resource_parameter_profile === 'compatibility' });
  checks.push({ name: 'Tenant objects configured', ok: Boolean(token) && actions.every(a => a.operation === 'unchanged') });
  return { mode: apply ? 'apply' : 'dry-run', ready: checks.every(c => c.ok), checks, actions, manualSteps };
}

async function main() {
  try {
    const args = process.argv.slice(2);
    if (args.includes('--help')) {
      console.log('node scripts/auth0/setup.mjs [--plan | --apply --credentials-dir PATH]\n기본: 공개 metadata 및 Auth0 설정 읽기 전용 점검. --plan: 네트워크 없는 설정 계획.');
      return;
    }
    const options = { log: message => console.error(message) };
    for (let i = 0; i < args.length; i++) {
      if (args[i] === '--plan') options.planOnly = true;
      else if (args[i] === '--apply') options.apply = true;
      else if (args[i] === '--credentials-dir' && args[i + 1] && !args[i + 1].startsWith('--')) options.credentialsDir = args[++i];
      else throw new SetupError('지원하지 않는 인자입니다. --help를 확인하세요.');
    }
    const result = await runSetup(process.env, options);
    console.log(JSON.stringify(result, null, 2));
    if (!options.planOnly && !result.ready) process.exitCode = 2;
  } catch (error) {
    console.error(error instanceof SetupError ? error.message : 'Auth0 설정 실패입니다. 내부 오류 상세는 비밀값 보호를 위해 숨겼습니다.');
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) await main();
