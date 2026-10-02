#!/usr/bin/env node
// Checks only: never starts containers/services, acquires tokens, or writes business data.
import { execFile } from 'node:child_process';
import { access, readdir } from 'node:fs/promises';
import { constants } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
import { readConfig, safeUrl } from '../../agents/runner/src/protocol.js';

const root = fileURLToPath(new URL('../../', import.meta.url));
const requiredRunner = ['MULINO_WORKER_TOKEN', 'MULINO_WORKER_ID', 'MULINO_API_BASE', 'MULINO_AGENT_API_URL', 'MULINO_RUNTIME_IMAGE',
  'MULINO_CODEX_AUTH_VOLUME', 'MULINO_CODEX_MODEL'];
const present = value => typeof value === 'string' && value.trim().length > 0;
function assert(ok) { if (!ok) throw new Error('CHECK_FAILED'); }
export function dbTarget(env) {
  // libpq query parameters can load files or change startup options: reject them all.
  const url = new URL(env.DB_URL?.replace(/^jdbc:/, ''));
  assert(['postgres:', 'postgresql:'].includes(url.protocol) && url.hostname && !url.username
    && !url.password && !url.search && !url.hash && /^\/[A-Za-z0-9_-]+$/.test(url.pathname)
    && /^[A-Za-z0-9.:[\]-]+$/.test(url.hostname)
    && present(env.DB_USERNAME) && present(env.DB_PASSWORD));
  return { PGHOST: url.hostname.replace(/^\[|\]$/g, ''), PGPORT: url.port || '5432',
    PGDATABASE: url.pathname.slice(1), PGUSER: env.DB_USERNAME, PGPASSWORD: env.DB_PASSWORD,
    PGCONNECT_TIMEOUT: '5', PGOPTIONS: '-c default_transaction_read_only=on -c statement_timeout=5000',
    PGSSLMODE: ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname) ? 'prefer' : 'verify-full' };
}
export function command(file, args, { env, timeoutMs = 8000 } = {}) {
  return new Promise((accept, reject) => {
    execFile(file, args, { env: env ?? { PATH: process.env.PATH, JAVA_HOME: process.env.JAVA_HOME },
      timeout: timeoutMs, killSignal: 'SIGKILL', maxBuffer: 262144, windowsHide: true }, (error, stdout, stderr) => {
      if (error) reject(new Error(error.code === 'ENOENT' ? 'MISSING_COMMAND' : 'COMMAND_FAILED'));
      else accept(`${stdout}\n${stderr}`.trim());
    });
  });
}
async function bounded(action, timeoutMs, onTimeout = () => {}) {
  let timer;
  try { return await Promise.race([Promise.resolve().then(action), new Promise((_, reject) => {
    timer = setTimeout(() => { onTimeout(); reject(new Error('TIMEOUT')); }, timeoutMs);
  })]); } finally { clearTimeout(timer); }
}
export async function runReadiness(env = process.env, deps = {}) {
  const run = deps.command ?? command;
  const fetchImpl = deps.fetch ?? fetch;
  const checkAccess = deps.access ?? access;
  const timeoutMs = deps.timeoutMs ?? 8000;
  const checks = [];
  const add = (component, status, message) => checks.push({ component, status, message });
  async function check(component, action) {
    try { await bounded(action, timeoutMs); add(component, 'PASS', '점검 통과 (설정/조회 범위).'); }
    catch (error) { add(component, error.message === 'MISSING_COMMAND' || error.code === 'ENOENT' ? 'MISSING' : 'FAILED',
      '점검하지 못했거나 기대 조건과 다릅니다. 설정·설치·연결·제한 시간을 확인하세요.'); }
  }
  function missing(component, names) {
    const absent = names.filter(name => !present(env[name]));
    if (absent.length) add(component, 'MISSING', `필요한 환경 변수: ${absent.join(', ')}`);
    return absent.length > 0;
  }
  async function json(url, headers = {}) {
    const abort = new AbortController();
    return bounded(async () => {
      const response = await fetchImpl(url, { method: 'GET', redirect: 'error', signal: abort.signal,
        headers: { Accept: 'application/json', ...headers } });
      assert(response.ok && !response.redirected);
      assert(Number(response.headers.get('content-length') ?? 0) <= 262144);
      const reader = response.body.getReader();
      let length = 0;
      const chunks = [];
      try {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          length += value.byteLength;
          assert(length <= 262144);
          chunks.push(value);
        }
        return JSON.parse(Buffer.concat(chunks).toString('utf8'));
      } finally { await reader.cancel().catch(() => {}); }
    }, timeoutMs, () => abort.abort());
  }
  await Promise.all([
    check('java', async () => assert(/version "21(?:\.|\")/.test(await run('java', ['-version'], { timeoutMs })))),
    check('node', async () => assert(Number((deps.nodeVersion ?? process.versions.node).split('.')[0]) >= 22)),
    check('zig', async () => assert(/^0\.16\.0$/.test(await run('zig', ['version'], { timeoutMs })))),
    check('docker', async () => assert(present(await run('docker', ['version', '--format', '{{.Server.Version}}'], { timeoutMs })))),
    check('cli', () => checkAccess(resolve(root, 'agents/cli/zig-out/bin/mulino'), constants.X_OK)),
  ]);
  if (!missing('database', ['DB_URL', 'DB_USERNAME', 'DB_PASSWORD'])) await check('database', async () => {
    const target = dbTarget(env);
    const files = await (deps.readdir ?? readdir)(resolve(root, 'backend/src/main/resources/db/migration'));
    const latest = Math.max(...files.map(name => /^V(\d+)__/.exec(name)?.[1]).filter(Boolean).map(Number));
    assert(Number.isFinite(latest));
    const sql = "BEGIN READ ONLY; SELECT current_setting('server_version_num'); SELECT coalesce(max(version::integer),0) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'; SELECT count(*) FROM flyway_schema_history WHERE NOT success; ROLLBACK;";
    const output = await run('psql', ['-X', '-w', '-qAt', '-v', 'ON_ERROR_STOP=1', '-c', sql], {
      timeoutMs, env: { PATH: process.env.PATH, ...target } });
    const lines = output.trim().split(/\s+/);
    assert(lines.length === 3 && /^18\d{4}$/.test(lines[0]) && Number(lines[1]) === latest && lines[2] === '0');
  });
  // PoC 로컬 신원: 역할 헤더로 MANAGER가 구매 결정 권한을 받는지만 본다. Auth0는 #21·#22에서 보류했다.
  if (!missing('backend-human', ['MULINO_API_BASE'])) await check('backend-human', async () => {
    const base = safeUrl(env.MULINO_API_BASE);
    const me = await json(`${base}/me`, { 'X-Mulino-Local-Role': 'MANAGER' });
    assert(me.actorType === 'HUMAN' && me.role === 'MANAGER' && me.capabilities?.includes('erp:read')
      && me.capabilities?.includes('procurement:decide'));
  });
  if (!missing('runner-config', requiredRunner)) await check('runner-config', () => { readConfig(env); });
  for (const [component, variable, kind] of [['runtime-image', 'MULINO_RUNTIME_IMAGE', 'image'], ['auth-volume', 'MULINO_CODEX_AUTH_VOLUME', 'volume']]) {
    if (!missing(component, [variable])) await check(component, async () => {
      assert((kind === 'image' ? /^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$/ : /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/).test(env[variable]));
      await run('docker', [kind, 'inspect', env[variable]], { timeoutMs });
    });
  }
  if (!missing('model-selection', ['MULINO_CODEX_MODEL'])) await check('model-selection', () => {
    assert(/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$/.test(env.MULINO_CODEX_MODEL));
  });
  for (const component of ['codex-volume-login', 'live-model-execution', 'per-decision-confirmation']) {
    add(component, 'UNVERIFIED', '실제 외부 인수 증거 필요. 이 점검은 로그인·모델 호출·결정 요청을 실행하지 않습니다.');
  }
  checks.sort((a, b) => a.component.localeCompare(b.component));
  return { mode: 'read-only', softwareConfigurationChecksPassed: checks.every(c => ['PASS', 'UNVERIFIED'].includes(c.status)),
    liveAcceptance: 'UNVERIFIED', ready: false, checks };
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  if (process.argv.slice(2).length) {
    process.stdout.write('Usage: node scripts/demo/readiness.mjs (checks only; configuration through environment)\n');
    process.exitCode = process.argv.slice(2).every(arg => arg === '--help') ? 0 : 1;
  } else {
    try {
      const report = await runReadiness();
      process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
      process.exitCode = report.softwareConfigurationChecksPassed ? 0 : 2;
    } catch {
      process.stderr.write('Readiness check failed. No provider output is displayed.\n');
      process.exitCode = 1;
    }
  }
}
