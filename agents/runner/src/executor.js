import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { StringDecoder } from 'node:string_decoder';
import { redact, runtimes, safeUrl, validateResult } from './protocol.js';
import { claudeArguments, codexConfiguration } from './runtime-config.js';

// Claude Code's --json-schema validator rejects the draft 2020-12 $schema URI; the schema uses no
// 2020-12-only keywords, so the declaration is dropped for Claude only (Codex reads the file as is).
const { $schema, ...resultSchemaBody } = JSON.parse(readFileSync(new URL('../result.schema.json', import.meta.url), 'utf8'));
const resultSchema = JSON.stringify(resultSchemaBody);

export class ExecutionError extends Error {
  constructor(code) { super(code); this.code = code; }
}

/** Injectable command builder for real subprocess tests. Production uses DockerExecutor. */
export class ProcessExecutor {
  constructor({ invocation, maxOutputBytes = 1_048_576, maxLineBytes = 65536 }) {
    Object.assign(this, { invocation, maxOutputBytes, maxLineBytes });
  }

  start(claim, { secrets = [] } = {}) {
    const spec = this.invocation(claim);
    const input = JSON.stringify({ runRef: claim.runRef, caseRef: claim.caseRef, workItemRef: claim.workItemRef,
      agentKey: claim.agentKey, context: claim.context });
    if (Buffer.byteLength(input) > 270336) throw new ExecutionError('CLAIM_CONTEXT_TOO_LARGE');
    const child = spawn(spec.command, spec.args, { shell: false, detached: true,
      stdio: ['pipe', 'pipe', 'pipe'], env: { ...spec.env } });
    let failure = null;
    let closed = false;
    let totalBytes = 0;
    let pending = '';
    let finalText = null;
    let cancellation = null;
    const usage = {}; // Filled from Claude Code's result event; cost and token counts are not secrets.
    const decoder = new StringDecoder('utf8');
    const kill = () => {
      if (!closed && child.pid) {
        try { process.kill(-child.pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') child.kill('SIGKILL'); }
      }
    };
    const cancel = async () => {
      failure ??= new ExecutionError('MODEL_CANCELLED');
      if (!cancellation) cancellation = Promise.resolve(spec.onCancel?.()).catch(() => {});
      kill();
      await cancellation;
      await result.catch(() => {});
    };
    const fail = code => { failure ??= new ExecutionError(code); void cancel(); };
    const line = text => {
      if (!text.trim()) return;
      if (Buffer.byteLength(text) > this.maxLineBytes) { fail('MODEL_OUTPUT_TOO_LARGE'); return; }
      try {
        const event = JSON.parse(text);
        if (!event || typeof event !== 'object' || typeof event.type !== 'string') throw new Error();
        if (event.type === 'item.completed' && event.item?.type === 'agent_message') {
          if (typeof event.item.text !== 'string') throw new Error();
          finalText = event.item.text;
        }
        if (event.type === 'error' || event.type === 'turn.failed') fail('MODEL_PROCESS_FAILED');
        // Claude Code --output-format json prints one result object; --json-schema fills structured_output.
        if (event.type === 'result') {
          if (event.is_error || event.subtype !== 'success') fail('MODEL_PROCESS_FAILED');
          else finalText = event.structured_output === undefined ? event.result : JSON.stringify(event.structured_output);
          const tokens = event.usage ?? {};
          for (const [key, value] of [['costUsd', event.total_cost_usd], ['inputTokens', tokens.input_tokens],
            ['outputTokens', tokens.output_tokens], ['cacheReadTokens', tokens.cache_read_input_tokens],
            ['cacheWriteTokens', tokens.cache_creation_input_tokens], ['turns', event.num_turns]]) {
            if (Number.isFinite(value)) usage[key] = value;
          }
          if (typeof finalText !== 'string') throw new Error();
        }
      } catch { fail('INVALID_MODEL_JSON'); }
    };
    const count = chunk => {
      totalBytes += chunk.length;
      if (totalBytes > this.maxOutputBytes) { fail('MODEL_OUTPUT_TOO_LARGE'); return false; }
      return !failure;
    };
    child.stdout.on('data', chunk => {
      if (!count(chunk)) return;
      pending += decoder.write(chunk);
      let offset;
      while ((offset = pending.indexOf('\n')) >= 0) {
        line(pending.slice(0, offset)); pending = pending.slice(offset + 1);
      }
      if (Buffer.byteLength(pending) > this.maxLineBytes) fail('MODEL_OUTPUT_TOO_LARGE');
    });
    child.stderr.on('data', count); // Count and discard diagnostics; never forward child logs.
    child.stdin.on('error', () => {}); // A child may exit before consuming all input.
    const result = new Promise((resolve, reject) => {
      child.on('error', () => { failure ??= new ExecutionError('MODEL_START_FAILED'); });
      child.on('close', code => {
        closed = true;
        if (!failure) line(pending + decoder.end());
        if (failure) return reject(failure);
        if (code !== 0) return reject(new ExecutionError('MODEL_PROCESS_FAILED'));
        try {
          const value = validateResult(JSON.parse(finalText));
          resolve(redact(value, [...secrets, claim.leaseToken, claim.capabilityToken]));
        } catch { reject(new ExecutionError('INVALID_MODEL_RESULT')); }
      });
    });
    result.catch(() => {});
    child.stdin.end(input);
    return { result, cancel, pid: child.pid, usage };
  }
}

export class DockerExecutor extends ProcessExecutor {
  constructor({ image, authVolume, model, runtime = 'CODEX', agentApiUrl = 'http://host.docker.internal:8080/api/v1', ...options }) {
    if (!/^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$/.test(image ?? '')
      || !/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(authVolume ?? '') || !runtimes.has(runtime)) throw new Error('INVALID_DOCKER_CONFIG');
    super({ ...options, invocation: claim => this.buildInvocation(claim) });
    if (model !== undefined && (typeof model !== 'string' || !/^[a-zA-Z0-9][a-zA-Z0-9._:/-]{0,127}$/.test(model))) throw new Error('INVALID_MODEL');
    Object.assign(this, { image, authVolume, model, runtime, agentApiUrl: safeUrl(agentApiUrl, { container: true }) });
  }

  buildInvocation(claim) {
    const name = `mulino-run-${randomUUID()}`;
    // DOCKER_HOST only locates the daemon (e.g. OrbStack has no /var/run/docker.sock); it is not a secret.
    const hostEnv = { PATH: process.env.PATH ?? '/usr/local/bin:/usr/bin:/bin',
      ...(process.env.DOCKER_HOST ? { DOCKER_HOST: process.env.DOCKER_HOST } : {}) };
    const home = this.runtime === 'CLAUDE' ? '/home/mulino/.claude' : '/home/mulino/.codex';
    const container = [
      'run', '--rm', '--interactive', '--name', name, '--read-only', '--user=10001:10001',
      '--cap-drop=ALL', '--security-opt=no-new-privileges', '--pids-limit=256', '--memory=2g', '--cpus=2',
      '--tmpfs', '/work:rw,nosuid,nodev,size=256m,uid=10001,gid=10001',
      '--tmpfs', '/tmp:rw,nosuid,nodev,size=256m,uid=10001,gid=10001',
      '--workdir=/work', '--mount', `type=volume,src=${this.authVolume},dst=${home}`,
      '--env', 'MULINO_TOKEN', '--env', `MULINO_API_URL=${this.agentApiUrl}`,
    ];
    const model = this.model ? ['--model', this.model] : [];
    const args = this.runtime === 'CLAUDE' ? [...container,
      // Claude Code keeps its login and ~/.claude.json together in the mounted volume.
      '--env', `CLAUDE_CONFIG_DIR=${home}`, '--env', 'HOME=/home/mulino', '--env', 'DISABLE_AUTOUPDATER=1',
      '--env', 'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1',
      '--entrypoint=claude', this.image, ...claudeArguments(claim.agentKey, resultSchema), ...model,
      'Carry out the Run described by the JSON on stdin.',
    ] : [...container,
      '--env', `CODEX_HOME=${home}`, '--env', 'HOME=/home/mulino',
      '--entrypoint=codex', this.image, 'exec', '--strict-config', '--ignore-user-config', '--ignore-rules', '--ephemeral',
      '--skip-git-repo-check', '--json', '--color=never', '--output-schema', '/opt/mulino/result.schema.json',
      // Docker is the isolation boundary. Nested bwrap cannot create user namespaces
      // under the container's non-root/cap-drop policy; never add privileged Docker flags.
      '--sandbox=danger-full-access',
      '--config', 'approval_policy="never"', '--config', 'mcp_servers={}',
      ...codexConfiguration(claim.agentKey).flatMap(value => ['--config', value]),
      ...model, '-',
    ];
    return { command: 'docker', env: { ...hostEnv, MULINO_TOKEN: claim.capabilityToken }, args,
      onCancel: () => new Promise(resolve => {
      // Killing the Docker client does not guarantee its container stopped. Remove it separately.
      const cleanup = spawn('docker', ['rm', '--force', name], { shell: false, stdio: 'ignore', env: hostEnv });
      const timeout = setTimeout(() => { cleanup.kill('SIGKILL'); resolve(); }, 5000);
      cleanup.once('error', () => { clearTimeout(timeout); resolve(); });
      cleanup.once('close', () => { clearTimeout(timeout); resolve(); });
    }) };
  }
}
