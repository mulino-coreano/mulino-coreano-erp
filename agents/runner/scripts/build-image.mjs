import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, copyFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const runner = fileURLToPath(new URL('..', import.meta.url));
const cli = resolve(runner, '../cli');
const tag = process.argv[2] ?? 'mulino-codex-runtime:0.151.0';
const env = { PATH: process.env.PATH, HOME: process.env.HOME };
const capture = (cmd, args) => execFileSync(cmd, args, { env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
const run = (cmd, args, options = {}) => {
  const result = spawnSync(cmd, args, { env, stdio: 'inherit', ...options });
  if (result.error || result.status !== 0) throw new Error('BUILD_STEP_FAILED');
};
let context;
try {
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$/.test(tag)) throw new Error('INVALID_IMAGE_TAG');
  if (capture('zig', ['version']) !== '0.16.0') throw new Error('ZIG_VERSION_MISMATCH');
  const architecture = capture('docker', ['info', '--format', '{{.Architecture}}']);
  const target = ({ aarch64: 'aarch64-linux-musl', arm64: 'aarch64-linux-musl',
    x86_64: 'x86_64-linux-musl', amd64: 'x86_64-linux-musl' })[architecture];
  if (!target) throw new Error('UNSUPPORTED_DOCKER_ARCHITECTURE');
  context = mkdtempSync(join(tmpdir(), 'mulino-runtime-build-'));
  // Docker receives an allowlisted context, never the repository or local environment files.
  const prefix = join(context, 'compile');
  run('zig', ['build', '-Doptimize=ReleaseSafe', `-Dtarget=${target}`, '--prefix', prefix], { cwd: cli });
  copyFileSync(join(prefix, 'bin/mulino'), join(context, 'mulino'));
  rmSync(prefix, { recursive: true });
  for (const file of ['Dockerfile', 'package.json', 'package-lock.json'])
    copyFileSync(join(runner, 'runtime', file), join(context, file));
  copyFileSync(join(runner, 'result.schema.json'), join(context, 'result.schema.json'));
  mkdirSync(join(context, 'skills'));
  copyFileSync(resolve(runner, '../skills/runtime.md'), join(context, 'skills/runtime.md'));
  for (const role of ['orchestrator', 'supply-chain', 'procurement', 'qc']) {
    mkdirSync(join(context, 'skills', role));
    copyFileSync(resolve(runner, '../skills', role, 'SKILL.md'), join(context, 'skills', role, 'SKILL.md'));
  }
  run('docker', ['build', '--tag', tag, context]);
  run('docker', ['run', '--rm', '--network=none', '--read-only', '--cap-drop=ALL',
    '--security-opt=no-new-privileges', tag, '--version']);
  process.stdout.write(`Built ${tag} for ${target}. No login or model request was performed.\n`);
} catch (error) {
  const safe = ['INVALID_IMAGE_TAG', 'ZIG_VERSION_MISMATCH', 'UNSUPPORTED_DOCKER_ARCHITECTURE', 'BUILD_STEP_FAILED'];
  process.stderr.write(`Runtime build failed: ${safe.includes(error.message) ? error.message : 'BUILD_PREREQUISITE_FAILED'}\n`);
  process.exitCode = 1;
} finally {
  if (context) rmSync(context, { recursive: true, force: true });
}
