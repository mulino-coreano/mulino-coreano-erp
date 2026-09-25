import { readConfig } from './protocol.js';
import { staticToken, WorkerApi } from './http.js';
import { DockerExecutor } from './executor.js';
import { Runner } from './runner.js';

try {
  const config = readConfig();
  const secrets = [config.workerToken];
  const api = new WorkerApi({ baseUrl: config.apiBase, tokenClient: staticToken(config.workerToken) });
  const executor = new DockerExecutor(config);
  const runner = new Runner({ api, executor, workerId: config.workerId, runtime: config.runtime, secrets,
    logger: event => process.stdout.write(`${JSON.stringify(event)}\n`) });
  const stop = () => { void runner.stop().catch(() => {}); };
  process.once('SIGTERM', stop);
  process.once('SIGINT', stop);
  await runner.loop();
  process.removeListener('SIGTERM', stop);
  process.removeListener('SIGINT', stop);
} catch {
  // Errors, environment values, HTTP bodies and child output must never reach the console.
  process.stderr.write('Mulino runner could not start or continue; check its configuration and backend availability.\n');
  process.exitCode = 1;
}
