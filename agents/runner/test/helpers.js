import http from 'node:http';

export const claim = (overrides = {}) => ({
  runRef: 'RUN-1', caseRef: 'CASE-1', workItemRef: 'WI-1', agentKey: 'SUPPLY_CHAIN',
  runtime: 'CODEX', leaseToken: 'lease-token', capabilityToken: 'cap-token', context: { objective: 'plan' },
  leaseExpiresAt: new Date(Date.now() + 60_000).toISOString(), timeoutSeconds: 600, ...overrides,
});
export const json = (res, body, status = 200) => res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(body));
export async function server(handler) {
  const app = http.createServer(async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    try { await handler(req, res, body ? JSON.parse(body) : null); }
    catch { res.writeHead(500).end(); }
  });
  await new Promise(resolve => app.listen(0, '127.0.0.1', resolve));
  return { url: `http://127.0.0.1:${app.address().port}`, close: () => new Promise(resolve => app.close(resolve)) };
}

export class ManualClock {
  constructor() { this.time = Date.parse('2026-09-05T00:00:00Z'); this.waiters = []; }
  now = () => this.time;
  sleep = (ms, signal) => new Promise((resolve, reject) => {
    const waiter = { at: this.time + ms, resolve: () => { cleanup(); resolve(); } };
    const abort = () => { cleanup(); reject(new Error('ABORTED')); };
    const cleanup = () => { this.waiters = this.waiters.filter(w => w !== waiter); signal?.removeEventListener('abort', abort); };
    if (signal?.aborted) return reject(new Error('ABORTED'));
    signal?.addEventListener('abort', abort, { once: true });
    this.waiters.push(waiter);
  });
  async advance(ms) {
    this.time += ms;
    for (const waiter of [...this.waiters]) if (waiter.at <= this.time) waiter.resolve();
    await new Promise(resolve => setImmediate(resolve));
  }
}
export async function until(predicate) {
  for (let i = 0; i < 500; i++) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 2));
  }
  throw new Error('Condition did not become true');
}
