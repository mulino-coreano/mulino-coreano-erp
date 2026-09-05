import { randomUUID } from 'node:crypto';
import { HttpError } from './http.js';
import { redact, terminalReceipt, validInstant, validateClaim, validateResult } from './protocol.js';

export const realClock = {
  now: Date.now,
  sleep: (ms, signal) => new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(new Error('ABORTED'));
    const clean = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
    const abort = () => { clean(); reject(new Error('ABORTED')); };
    const timer = setTimeout(() => { clean(); resolve(); }, Math.max(0, ms));
    signal?.addEventListener('abort', abort, { once: true });
  }),
};
const transient = error => !error.status || error.status >= 500 || error.status === 429;

export class Runner {
  constructor({ api, executor, workerId, clock = realClock, pollMs = 5000, heartbeatMs = 15000,
    maxRunMs = 600000, retryMs = 1000, logger = () => {}, secrets = [] }) {
    if (typeof workerId !== 'string' || !workerId || workerId.length > 128
      || ![pollMs, heartbeatMs, maxRunMs, retryMs].every(n => Number.isFinite(n) && n > 0)
      || maxRunMs > 600000) throw new Error('INVALID_RUNNER_CONFIG');
    Object.assign(this, { api, executor, workerId, clock, pollMs, heartbeatMs, maxRunMs, retryMs, logger, secrets });
    this.shutdown = new AbortController();
    this.pending = null;
    this.cooldownUntil = 0;
  }

  async runOnce() {
    if (this.shutdown.signal.aborted) return { status: 'STOPPED' };
    if (this.pending) return { status: 'BUSY' };
    if (this.clock.now() < this.cooldownUntil) return { status: 'COOLDOWN' };
    this.pending = this.executeOne();
    try { return await this.pending; }
    finally { this.pending = null; }
  }

  async executeOne() {
    let claim;
    try {
      claim = await this.api.post('claim', { workerId: this.workerId }, {
        idempotencyKey: randomUUID(), signal: this.shutdown.signal,
      });
    } catch (error) {
      if (this.shutdown.signal.aborted) return { status: 'STOPPED' };
      if (transient(error) || error.status === 409) {
        // Claim issues unrecoverable random credentials. Retrying cannot safely replay them.
        this.cooldownUntil = this.clock.now() + 60000;
        this.log('claim_uncertain');
        return { status: 'CLAIM_UNCERTAIN' };
      }
      throw error;
    }
    if (claim === null) return { status: 'IDLE' };
    try { validateClaim(claim, this.clock.now()); }
    catch {
      if (typeof claim?.runRef === 'string' && typeof claim?.leaseToken === 'string'
        && validInstant(claim.leaseExpiresAt) && Date.parse(claim.leaseExpiresAt) > this.clock.now()) {
        return this.finish(claim, { outcome: 'FAILED', summary: 'INVALID_CLAIM' }, Date.parse(claim.leaseExpiresAt));
      }
      this.cooldownUntil = this.clock.now() + 60000;
      return { status: 'INVALID_CLAIM' };
    }
    let leaseDeadline = Date.parse(claim.leaseExpiresAt);
    const runDeadline = this.clock.now() + Math.min(this.maxRunMs, claim.timeoutSeconds * 1000);
    let heartbeatAt = this.clock.now() + this.heartbeatMs;
    let heartbeatKey = randomUUID();
    const credentials = { runRef: claim.runRef, workerId: this.workerId, leaseToken: claim.leaseToken };
    const secretValues = [...this.secrets, claim.capabilityToken, claim.leaseToken];
    let handle;
    try { handle = this.executor.start(claim, { secrets: this.secrets }); }
    catch { return this.finish(claim, { outcome: 'FAILED', summary: 'MODEL_START_FAILED' }, leaseDeadline); }
    this.log('run_started', { runRef: claim.runRef }, secretValues);
    const child = handle.result.then(value => ({ kind: 'result', value }), error => ({ kind: 'failure', error }));
    try {
      while (true) {
        if (this.shutdown.signal.aborted) {
          await handle.cancel('SHUTDOWN');
          return this.finish(claim, { outcome: 'ABORTED', summary: 'WORKER_SHUTDOWN' },
            Math.min(leaseDeadline, this.clock.now() + 5000));
        }
        if (this.clock.now() >= leaseDeadline) {
          await handle.cancel('LEASE_EXPIRED');
          return { status: 'LEASE_EXPIRED', runRef: claim.runRef };
        }
        if (this.clock.now() >= runDeadline) {
          await handle.cancel('TIMEOUT');
          return this.finish(claim, { outcome: 'ABORTED', summary: 'MODEL_TIMEOUT' }, leaseDeadline);
        }
        const timer = new AbortController();
        const signal = AbortSignal.any([timer.signal, this.shutdown.signal]);
        const event = await Promise.race([child, this.clock.sleep(Math.max(0,
          Math.min(heartbeatAt, leaseDeadline, runDeadline) - this.clock.now()), signal)
          .then(() => ({ kind: 'tick' }), () => ({ kind: 'stop' }))]);
        timer.abort();
        if (event.kind === 'stop') continue;
        if (event.kind === 'result' || event.kind === 'failure') {
          let result;
          try {
            result = event.kind === 'result' ? validateResult(event.value) : {
              outcome: 'FAILED', summary: 'MODEL_EXECUTION_FAILED',
            };
          } catch { result = { outcome: 'FAILED', summary: 'INVALID_MODEL_RESULT' }; }
          return this.finish(claim, redact(result, [...secretValues, ...this.secrets]), leaseDeadline);
        }
        if (this.clock.now() >= leaseDeadline || this.clock.now() >= runDeadline) continue;
        try {
          const receipt = await this.requestBefore('heartbeat', credentials, heartbeatKey,
            Math.min(leaseDeadline, runDeadline), this.shutdown.signal);
          if (receipt?.runRef !== claim.runRef) throw new HttpError('INVALID_HEARTBEAT_RESPONSE', 422);
          if (terminalReceipt(receipt)) {
            await handle.cancel('ALREADY_FINISHED');
            return this.recordTerminal(claim, receipt);
          }
          const expiry = Date.parse(receipt?.leaseExpiresAt);
          if (receipt?.status !== 'RUNNING' || !validInstant(receipt?.leaseExpiresAt) || expiry <= this.clock.now()) {
            throw new HttpError('INVALID_HEARTBEAT_RESPONSE', 422);
          }
          leaseDeadline = expiry;
          heartbeatAt = this.clock.now() + this.heartbeatMs;
          heartbeatKey = randomUUID();
        } catch (error) {
          if (this.shutdown.signal.aborted) continue;
          if (error.status === 409 || error.status === 401 || error.status === 403 || !transient(error)) {
            await handle.cancel('LEASE_LOST');
            return { status: 'LEASE_LOST', runRef: claim.runRef };
          }
          heartbeatAt = Math.min(this.clock.now() + this.retryMs, leaseDeadline);
        }
      }
    } finally { await handle.cancel('CLEANUP'); }
  }

  async requestBefore(action, body, key, deadline, signal) {
    if (this.clock.now() >= deadline) throw new HttpError('LEASE_DEADLINE_REACHED');
    const controller = new AbortController();
    const combined = AbortSignal.any([controller.signal, ...(signal ? [signal] : [])]);
    try {
      return await Promise.race([
        this.api.post(action, body, { idempotencyKey: key, signal: combined }),
        this.clock.sleep(deadline - this.clock.now(), combined).then(() => { throw new HttpError('LEASE_DEADLINE_REACHED'); }),
      ]);
    } finally { controller.abort(); }
  }

  async finish(claim, result, deadline) {
    let key = randomUUID();
    const credentials = { runRef: claim.runRef, workerId: this.workerId, leaseToken: claim.leaseToken };
    let body = { ...credentials, ...result };
    let failureFallbackUsed = false;
    while (this.clock.now() < deadline) {
      try {
        const receipt = await this.requestBefore('finish', body, key, deadline);
        if (receipt?.runRef !== claim.runRef || !terminalReceipt(receipt)) throw new HttpError('INVALID_FINISH_RESPONSE', 422);
        return this.recordTerminal(claim, receipt);
      } catch (error) {
        const invalidResult = (error.status === 409 && error.backendCode === 'COMPLETION_NOT_VERIFIED')
          || (error.status === 400 && error.backendCode === 'INVALID_RESULT');
        if (invalidResult && !failureFallbackUsed) {
          failureFallbackUsed = true;
          const verification = await this.verifyFinishLease(claim, deadline);
          if (verification.receipt) return this.recordTerminal(claim, verification.receipt);
          if (verification.status) return verification;
          deadline = verification.deadline;
          // This is a new logical mutation. Network retries below reuse this new key and body.
          key = randomUUID();
          body = { ...credentials, outcome: 'FAILED', summary: error.backendCode };
          continue;
        }
        if (!transient(error)) {
          const status = (error.status === 409 && !invalidResult) || [401, 403].includes(error.status)
            ? 'LEASE_LOST' : 'FINISH_REJECTED';
          this.log('finish_rejected', { runRef: claim.runRef, status }, [claim.leaseToken, claim.capabilityToken]);
          return { status, runRef: claim.runRef };
        }
        if (this.clock.now() >= deadline) break;
        await this.clock.sleep(Math.min(this.retryMs, deadline - this.clock.now()));
      }
    }
    return { status: 'FINISH_UNCERTAIN', runRef: claim.runRef };
  }

  async verifyFinishLease(claim, deadline) {
    const key = randomUUID();
    const body = { runRef: claim.runRef, workerId: this.workerId, leaseToken: claim.leaseToken };
    while (this.clock.now() < deadline) {
      try {
        const receipt = await this.requestBefore('heartbeat', body, key, deadline);
        if (receipt?.runRef !== claim.runRef) throw new HttpError('INVALID_HEARTBEAT_RESPONSE', 422);
        if (terminalReceipt(receipt)) return { receipt };
        if (receipt?.status !== 'RUNNING' || !validInstant(receipt.leaseExpiresAt)
          || Date.parse(receipt.leaseExpiresAt) <= this.clock.now()) throw new HttpError('INVALID_HEARTBEAT_RESPONSE', 422);
        const renewed = Date.parse(receipt.leaseExpiresAt);
        return { deadline: this.shutdown.signal.aborted ? Math.min(deadline, renewed) : renewed };
      } catch (error) {
        if (!transient(error)) return { status: 'LEASE_LOST', runRef: claim.runRef };
        if (this.clock.now() >= deadline) break;
        await this.clock.sleep(Math.min(this.retryMs, deadline - this.clock.now()));
      }
    }
    return { status: 'FINISH_UNCERTAIN', runRef: claim.runRef };
  }

  recordTerminal(claim, receipt) {
    const secrets = [...this.secrets, claim.leaseToken, claim.capabilityToken];
    this.log('run_finished', { runRef: claim.runRef, outcome: receipt.outcome }, secrets);
    return redact(receipt, secrets);
  }

  log(event, details = {}, secrets = []) { this.logger(redact({ event, ...details }, [...this.secrets, ...secrets])); }

  async loop({ signal } = {}) {
    const stop = () => { void this.stop(); };
    signal?.addEventListener('abort', stop, { once: true });
    if (signal?.aborted) stop();
    try {
      while (!this.shutdown.signal.aborted) {
        try { await this.runOnce(); }
        catch { this.log('worker_request_failed'); }
        if (!this.shutdown.signal.aborted) await this.clock.sleep(
          Math.max(this.pollMs, this.cooldownUntil - this.clock.now()), this.shutdown.signal).catch(() => {});
      }
    } finally { signal?.removeEventListener('abort', stop); await this.stop(); }
  }

  async stop() { this.shutdown.abort(); await this.pending; }
}
