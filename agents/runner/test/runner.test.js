import test from 'node:test';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { Runner } from '../src/runner.js';
import { staticToken, WorkerApi } from '../src/http.js';
import { ProcessExecutor } from '../src/executor.js';
import { claim, server, json, ManualClock, until } from './helpers.js';

const fixture = fileURLToPath(new URL('./fixtures/child.js', import.meta.url));
const receipt = (outcome = 'DONE') => ({ runRef: 'RUN-1', status: ['DONE', 'WAITING'].includes(outcome) ? 'COMPLETED' : outcome,
  outcome, alreadyFinished: false, leaseExpiresAt: null });
async function setup(t, { mode = 'done', route = () => false } = {}) {
  const calls = [];
  const handles = [];
  const clock = new ManualClock();
  const app = await server(async (req, res, body) => {
    const action = req.url.split('/').at(-1);
    calls.push({ action, body, key: req.headers['idempotency-key'] });
    if (await route({ action, req, res, body, calls, clock })) return;
    if (action === 'claim') json(res, claim({ leaseExpiresAt: new Date(clock.now() + 60_000).toISOString() }));
    else if (action === 'heartbeat') json(res, { runRef: 'RUN-1', status: 'RUNNING', outcome: null,
      leaseExpiresAt: new Date(clock.now() + 60_000).toISOString(), alreadyFinished: false });
    else json(res, receipt(body.outcome));
  });
  const processExecutor = new ProcessExecutor({ invocation: () => ({ command: process.execPath, args: [fixture, mode], env: {} }) });
  const executor = { start: (...args) => { const handle = processExecutor.start(...args); handles.push(handle); return handle; } };
  const api = new WorkerApi({ baseUrl: `${app.url}/api/v1`, tokenClient: { getToken: async () => 'worker-access' } });
  const runner = new Runner({ api, executor, workerId: 'worker-1', clock });
  t.after(async () => { await runner.stop(); await Promise.all(handles.map(handle => handle.cancel())); await app.close(); });
  return { runner, calls, handles, clock };
}

test('204 is idle and a real child success is reported through finish', async t => {
  let idle = true;
  const { runner, calls } = await setup(t, { route: ({ action, res }) => {
    if (action === 'claim' && idle) { res.writeHead(204).end(); idle = false; return true; }
  } });
  assert.equal((await runner.runOnce())?.status, 'IDLE');
  assert.equal((await runner.runOnce())?.outcome, 'DONE');
  assert.deepEqual(calls.map(c => c.action), ['claim', 'claim', 'finish']);
  assert.equal(calls.at(-1).body.resultRef, 'PLAN-1');
  assert.ok(calls.every(c => c.key));
  assert.ok(calls.filter(c => c.action === 'claim').every(c => c.body.runtime === 'CODEX'));
});

test('saved approval wait uses the original server receipt without creating model-supplied approval conditions', async t => {
  const {runner,calls}=await setup(t,{mode:'approval',route:({action,res})=>{
    if(action==='finish'){json(res,{...receipt('WAITING'),alreadyFinished:true});return true;}
  }});
  assert.equal((await runner.runOnce()).outcome,'WAITING');
  const finish=calls.filter(call=>call.action==='finish');
  assert.equal(finish.length,1);assert.equal(finish[0].body.outcome,'WAITING');
  assert.deepEqual(finish[0].body.waitingConditions,[]);
});

test('an invented approval reference cannot put an active run into approval waiting', async t => {
  const {runner,calls}=await setup(t,{mode:'approval',route:({action,res,body})=>{
    if(action==='finish'&&body.outcome==='WAITING'){json(res,{error:'INVALID_RESULT'},400);return true;}
  }});
  assert.equal((await runner.runOnce()).outcome,'FAILED');
  assert.deepEqual(calls.filter(call=>call.action==='finish').map(call=>call.body.outcome),['WAITING','FAILED']);
});

test('one in-flight child, heartbeat renews lease, stop reports ABORTED', async t => {
  const { runner, calls, handles, clock } = await setup(t, { mode: 'hang' });
  const running = runner.runOnce();
  await until(() => handles.length === 1);
  assert.equal((await runner.runOnce())?.status, 'BUSY');
  await clock.advance(15_000);
  await until(() => calls.some(c => c.action === 'heartbeat'));
  await runner.stop();
  assert.equal((await running)?.outcome, 'ABORTED');
  assert.equal(calls.filter(c => c.action === 'claim').length, 1);
  assert.equal(calls.at(-1).body.outcome, 'ABORTED');
});

test('run timeout kills real child and reports ABORTED', async t => {
  const { runner, calls, handles, clock } = await setup(t, { mode: 'hang' });
  runner.maxRunMs = 1000;
  const running = runner.runOnce();
  await until(() => handles.length === 1);
  await clock.advance(1000);
  assert.equal((await running)?.outcome, 'ABORTED');
  assert.match(calls.at(-1).body.summary, /TIMEOUT/);
});

test('lease conflict kills child without a late finish mutation', async t => {
  const { runner, calls, handles, clock } = await setup(t, { mode: 'hang', route: ({ action, res }) => {
    if (action === 'heartbeat') { json(res, {}, 409); return true; }
  } });
  const running = runner.runOnce();
  await until(() => handles.length === 1);
  await clock.advance(15_000);
  assert.equal((await running)?.status, 'LEASE_LOST');
  assert.equal(calls.filter(c => c.action === 'finish').length, 0);
});

test('heartbeat transport failure retries with same key and stops at lease deadline', async t => {
  const { runner, calls, handles, clock } = await setup(t, { mode: 'hang', route: ({ action, res }) => {
    if (action === 'heartbeat') { json(res, {}, 503); return true; }
  } });
  const running = runner.runOnce();
  await until(() => handles.length === 1);
  await clock.advance(15_000);
  await until(() => calls.filter(c => c.action === 'heartbeat').length === 1);
  await until(() => clock.waiters.some(waiter => waiter.at === clock.now() + 1000));
  await clock.advance(1000);
  await until(() => calls.filter(c => c.action === 'heartbeat').length === 2);
  await clock.advance(44_000);
  assert.equal((await running)?.status, 'LEASE_EXPIRED');
  assert.equal(calls.filter(c => c.action === 'finish').length, 0);
  const heartbeatKeys = calls.filter(c => c.action === 'heartbeat').map(c => c.key);
  assert.equal(new Set(heartbeatKeys).size, 1);
});

test('terminal heartbeat receipt wins and child gets terminated without downgrade', async t => {
  const { runner, calls, handles, clock } = await setup(t, { mode: 'hang', route: ({ action, res }) => {
    if (action === 'heartbeat') { json(res, { ...receipt('WAITING'), alreadyFinished: true }); return true; }
  } });
  const running = runner.runOnce();
  await until(() => handles.length === 1);
  await clock.advance(15_000);
  assert.equal((await running)?.outcome, 'WAITING');
  assert.equal(calls.filter(c => c.action === 'finish').length, 0);
});

test('crash after business commit preserves original finish receipt', async t => {
  const { runner, calls } = await setup(t, { mode: 'fail', route: ({ action, res }) => {
    if (action === 'finish') { json(res, { ...receipt('DONE'), alreadyFinished: true }); return true; }
  } });
  assert.equal((await runner.runOnce())?.outcome, 'DONE');
  assert.equal(calls.at(-1).body.outcome, 'FAILED');
});

test('lost claim and worker collision both enforce 60-second cooldown before fresh claim', async t => {
  let attempts = 0;
  const { runner, calls, clock } = await setup(t, { route: ({ action, req, res }) => {
    if (action !== 'claim') return false;
    attempts++;
    if (attempts === 1) { req.socket.destroy(); return true; }
    if (attempts === 2) { json(res, {}, 409); return true; }
    res.writeHead(204).end(); return true;
  } });
  assert.equal((await runner.runOnce())?.status, 'CLAIM_UNCERTAIN');
  assert.equal((await runner.runOnce())?.status, 'COOLDOWN');
  assert.equal(calls.length, 1);
  await clock.advance(60_000);
  assert.equal((await runner.runOnce())?.status, 'CLAIM_UNCERTAIN');
  assert.equal((await runner.runOnce())?.status, 'COOLDOWN');
  await clock.advance(60_000);
  assert.equal((await runner.runOnce())?.status, 'IDLE');
  assert.equal(new Set(calls.map(c => c.key)).size, 3);
});

test('invalid role never starts child and is failed explicitly', async t => {
  const { runner, calls, handles, clock } = await setup(t, { route: ({ action, res, clock }) => {
    if (action === 'claim') { json(res, claim({ agentKey: 'ADMIN', leaseExpiresAt: new Date(clock.now() + 60_000).toISOString() })); return true; }
  } });
  assert.equal((await runner.runOnce())?.outcome, 'FAILED');
  assert.equal(handles.length, 0);
  assert.equal(calls.at(-1).body.summary, 'INVALID_CLAIM');
});

test('poll loop stops on external signal and does not claim after stopping', async t => {
  const { runner, calls, clock } = await setup(t, { route: ({ res }) => { res.writeHead(204).end(); return true; } });
  const signal = new AbortController();
  const loop = runner.loop({ signal: signal.signal });
  await until(() => calls.length === 1);
  await until(() => clock.waiters.length > 0);
  signal.abort();
  await loop;
  assert.equal((await runner.runOnce())?.status, 'STOPPED');
  assert.equal(calls.length, 1);
});

test('transient finish failure reuses its exact body and key before accepting receipt', async t => {
  let failed = false;
  const { runner, calls, clock } = await setup(t, { route: ({ action, res }) => {
    if (action === 'finish' && !failed) { failed = true; json(res, {}, 503); return true; }
  } });
  const running = runner.runOnce();
  await until(() => clock.waiters.some(waiter => waiter.at === clock.now() + 1000));
  await clock.advance(1000);
  assert.equal((await running)?.outcome, 'DONE');
  const finishes = calls.filter(call => call.action === 'finish');
  assert.equal(finishes.length, 2);
  assert.equal(finishes[0].key, finishes[1].key);
  assert.deepEqual(finishes[0].body, finishes[1].body);
});

test('network request that never returns is aborted exactly at lease deadline', async t => {
  const { runner, calls, handles, clock } = await setup(t, { mode: 'hang', route: ({ action }) => action === 'heartbeat' });
  const running = runner.runOnce();
  await until(() => handles.length === 1);
  await clock.advance(15_000);
  await until(() => calls.some(call => call.action === 'heartbeat'));
  await clock.advance(45_000);
  assert.equal((await running)?.status, 'LEASE_EXPIRED');
  assert.equal(calls.filter(call => call.action === 'finish').length, 0);
});

test('known lease credentials are redacted even when echoed in server reference fields', async t => {
  const logs = [];
  const { runner } = await setup(t, { route: ({ action, res, clock }) => {
    if (action === 'claim') {
      json(res, claim({ runRef: 'lease-token', leaseExpiresAt: new Date(clock.now() + 60_000).toISOString() })); return true;
    }
    if (action === 'finish') { json(res, { ...receipt(), runRef: 'lease-token' }); return true; }
  } });
  runner.logger = event => logs.push(event);
  assert.equal((await runner.runOnce()).outcome, 'DONE');
  assert.doesNotMatch(JSON.stringify(logs), /lease-token|cap-token/);
});

test('loopback backend coordinates a complete real-child run with the worker token kept outside it', async t => {
  const finishes = [];
  const backend = await server((req, res, body) => {
    assert.equal(req.headers.authorization, 'Bearer worker-token-only');
    if (req.url.endsWith('/claim')) json(res, claim());
    else { finishes.push(body); json(res, receipt()); }
  });
  t.after(backend.close);
  const api = new WorkerApi({ baseUrl: `${backend.url}/api/v1`, tokenClient: staticToken('worker-token-only') });
  const executor = new ProcessExecutor({ invocation: () => ({ command: process.execPath, args: [fixture, 'env'],
    env: { MULINO_TOKEN: 'cap-token', MULINO_API_URL: 'http://127.0.0.1:8080/api/v1' } }) });
  const runner = new Runner({ api, executor, workerId: 'w', secrets: ['worker-token-only'] });
  t.after(() => runner.stop());
  assert.equal((await runner.runOnce()).outcome, 'DONE');
  assert.equal(finishes.length, 1);
  assert.doesNotMatch(finishes[0].summary, /worker-token-only|lease-token|cap-token/);
  assert.match(finishes[0].summary, /REDACTED/);
});

for (const mode of ['wait17', 'date-only']) {
  test(`invalid child ${mode} is finalized FAILED without sending unsupported waits`, async t => {
    const { runner, calls } = await setup(t, { mode });
    assert.equal((await runner.runOnce()).outcome, 'FAILED');
    const finishes = calls.filter(call => call.action === 'finish');
    assert.equal(finishes.length, 1);
    assert.equal(finishes[0].body.outcome, 'FAILED');
    assert.equal(finishes[0].body.waitingConditions, undefined);
  });
}

test('unverified completion checks lease then durably fails with a new logical finish key', async t => {
  const { runner, calls } = await setup(t, { route: ({ action, res, body }) => {
    if (action === 'finish' && body.outcome === 'DONE') {
      json(res, { error: 'COMPLETION_NOT_VERIFIED', message: 'private-reason' }, 409); return true;
    }
  } });
  assert.equal((await runner.runOnce()).outcome, 'FAILED');
  assert.deepEqual(calls.map(call => call.action), ['claim', 'finish', 'heartbeat', 'finish']);
  const finishes = calls.filter(call => call.action === 'finish');
  assert.notEqual(finishes[0].key, finishes[1].key);
  assert.equal(finishes[1].body.outcome, 'FAILED');
  assert.match(finishes[1].body.summary, /COMPLETION_NOT_VERIFIED/);
  assert.equal(finishes[1].body.resultRef, undefined);
});

test('invalid result response is finalized FAILED only once after active lease check', async t => {
  const { runner, calls } = await setup(t, { route: ({ action, res, body }) => {
    if (action === 'finish') { json(res, { error: 'INVALID_RESULT' }, 400); return true; }
  } });
  assert.equal((await runner.runOnce()).status, 'FINISH_REJECTED');
  assert.deepEqual(calls.map(call => call.action), ['claim', 'finish', 'heartbeat', 'finish']);
  const finishes = calls.filter(call => call.action === 'finish');
  assert.equal(finishes[1].body.outcome, 'FAILED');
  assert.notEqual(finishes[0].key, finishes[1].key);
});

test('stale finish never attempts failure fallback or another write', async t => {
  const { runner, calls } = await setup(t, { route: ({ action, res }) => {
    if (action === 'finish') { json(res, { error: 'STALE_LEASE' }, 409); return true; }
  } });
  assert.equal((await runner.runOnce()).status, 'LEASE_LOST');
  assert.deepEqual(calls.map(call => call.action), ['claim', 'finish']);
});

test('business completion racing rejection wins through authoritative heartbeat receipt', async t => {
  const { runner, calls } = await setup(t, { route: ({ action, res }) => {
    if (action === 'finish') { json(res, { error: 'COMPLETION_NOT_VERIFIED' }, 409); return true; }
    if (action === 'heartbeat') { json(res, { ...receipt('WAITING'), alreadyFinished: true }); return true; }
  } });
  assert.equal((await runner.runOnce()).outcome, 'WAITING');
  assert.deepEqual(calls.map(call => call.action), ['claim', 'finish', 'heartbeat']);
});

test('lease lost during rejection verification blocks failure fallback', async t => {
  const { runner, calls } = await setup(t, { route: ({ action, res }) => {
    if (action === 'finish') { json(res, { error: 'COMPLETION_NOT_VERIFIED' }, 409); return true; }
    if (action === 'heartbeat') { json(res, { error: 'STALE_LEASE' }, 409); return true; }
  } });
  assert.equal((await runner.runOnce()).status, 'LEASE_LOST');
  assert.deepEqual(calls.map(call => call.action), ['claim', 'finish', 'heartbeat']);
});

test('model failure code and usage are logged without child output', async t => {
  const { runner } = await setup(t, { mode: 'fail' });
  const logs = [];
  runner.logger = event => logs.push(event);
  assert.equal((await runner.runOnce()).outcome, 'FAILED');
  const finished = logs.find(event => event.event === 'model_finished');
  assert.equal(finished.failure, 'MODEL_PROCESS_FAILED');
  assert.doesNotMatch(JSON.stringify(logs), /secret-from-stderr/);
});
