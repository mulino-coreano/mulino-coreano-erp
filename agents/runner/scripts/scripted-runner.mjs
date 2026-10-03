// SIT 전용 실행기: 실제 Runner·WorkerApi에 스크립트 에이전트를 끼운다. 모델·하네스는 쓰지 않는다.
import { fileURLToPath } from "node:url";
import { Runner } from "../src/runner.js";
import { ProcessExecutor } from "../src/executor.js";
import { WorkerApi } from "../src/http.js";

const agent = fileURLToPath(new URL("./scripted-agent.mjs", import.meta.url));
const env = process.env;
const executor = new ProcessExecutor({ invocation: claim => ({
  command: process.execPath, args: [agent],
  env: { PATH: env.PATH, MULINO_TOKEN: claim.capabilityToken, MULINO_API_URL: env.MULINO_API_BASE,
    DEMO_CLI: env.DEMO_CLI, DEMO_PLAN_INPUT: env.DEMO_PLAN_INPUT },
}) });
const runner = new Runner({
  api: new WorkerApi({ baseUrl: env.MULINO_API_BASE, serviceSecret: env.MULINO_LOCAL_SERVICE_SECRET }),
  executor, workerId: env.MULINO_WORKER_ID ?? "scenario-scripted", pollMs: 200, maxRunMs: 30000,
  logger: event => process.stdout.write(`${JSON.stringify(event)}\n`),
});
process.once("SIGTERM", () => { void runner.stop(); });
await runner.loop();
