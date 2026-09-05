import { readConfig } from './protocol.js';
import { Auth0TokenClient, WorkerApi } from './http.js';
import { DockerExecutor } from './executor.js';
import { Runner } from './runner.js';

try {
  const config = readConfig();
  // Redaction retains a bounded set of recently issued tokens only in this process.
  const secrets = [config.clientSecret];
  const tokenClient = new Auth0TokenClient({ ...config, onToken: token => {
    secrets.push(token);
    if (secrets.length > 17) secrets.splice(1, secrets.length - 17);
  } });
  const api = new WorkerApi({ baseUrl: config.apiBase, tokenClient });
  const executor = new DockerExecutor(config);
  const runner = new Runner({ api, executor, workerId: config.workerId, secrets,
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
