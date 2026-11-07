let input = '';
for await (const chunk of process.stdin) input += chunk;
const mode = process.argv[2];
const send = result => process.stdout.write(JSON.stringify({ type: 'item.completed', item: {
  type: 'agent_message', text: JSON.stringify(result),
} }) + '\n');
if (mode === 'hang') setInterval(() => {}, 1000);
else if (mode === 'bad') process.stdout.write('not-json\n');
else if (mode === 'oversize') process.stdout.write('x'.repeat(20000));
else if (mode === 'fail') { process.stderr.write('secret-from-stderr'); process.exitCode = 1; }
else if (mode === 'env') send({ outcome: 'DONE', summary: JSON.stringify(process.env) });
else if (mode === 'context') send({ outcome: 'DONE', summary: JSON.parse(input).context.objective });
else if (mode === 'approval') send({ outcome: 'WAITING', summary: 'Saved purchase proposal', waitingConditions: [], resultRef: 'APPROVAL-42' });
else if (mode === 'wait17') send({ outcome: 'WAITING', summary: 'Pending', waitingConditions: Array(17).fill(
  { type: 'DEPENDENCY_DONE', payload: { dependentWiRef: 'WI-2' }, reason: 'Procurement' }) });
else if (mode === 'date-only') send({ outcome: 'WAITING', summary: 'Pending', waitingConditions: [
  { type: 'SCHEDULED_TIME', payload: { dueAt: '2026-09-06' }, reason: 'Receipt' }] });
// Claude Code stream-json: progress events, then one result event carrying the schema output.
else if (mode === 'claude') process.stdout.write([{ type: 'system', subtype: 'init' }, { type: 'assistant' },
  { type: 'result', subtype: 'success', is_error: false, result: 'ignored when structured',
    total_cost_usd: 0.12, num_turns: 3, modelUsage: { 'claude-sonnet-5': {} }, usage: { input_tokens: 10, output_tokens: 20 },
    structured_output: { outcome: 'DONE', summary: JSON.parse(input).context.objective, resultRef: 'PLAN-7' } }]
  .map(event => JSON.stringify(event)).join('\n') + '\n');
// A Claude Code session whose native subagent ran on a costlier model: modelUsage has one key per model.
else if (mode === 'claude-subagents') process.stdout.write(JSON.stringify({ type: 'result', subtype: 'success', is_error: false,
  total_cost_usd: 0.25, num_turns: 4, usage: { input_tokens: 100, output_tokens: 50, cache_read_input_tokens: 10, cache_creation_input_tokens: 5 },
  modelUsage: { 'claude-sonnet-5': { costUSD: 0.05 }, 'claude-opus-5': { costUSD: 0.20 } },
  structured_output: { outcome: 'DONE', summary: 'Completed with subagent', resultRef: 'PLAN-1' } }) + '\n');
else if (mode === 'claude-unpriced-subagents') process.stdout.write(JSON.stringify({ type: 'result', subtype: 'success', is_error: false,
  modelUsage: { 'claude-sonnet-5': {}, 'claude-opus-5': {} },
  structured_output: { outcome: 'DONE', summary: 'Completed with subagent', resultRef: 'PLAN-1' } }) + '\n');
else if (mode === 'codex-usage') {
  send({ outcome: 'DONE', summary: 'Finished', resultRef: 'PLAN-1' });
  process.stdout.write(JSON.stringify({type:'turn.completed',usage:{input_tokens:41,cached_input_tokens:7,output_tokens:13}})+'\n');
}
// codex exec --json puts a command's whole aggregated_output on one item.completed line.
else if (mode === 'codex-large-tool-output') {
  const plan = JSON.stringify({ ref: 'PLAN-DEMO', snapshot: 'x'.repeat(100_000) });
  process.stdout.write(JSON.stringify({ type: 'item.completed', item: { type: 'command_execution',
    command: 'mulino plan show PLAN-DEMO', aggregated_output: plan, exit_code: 0 } }) + '\n');
  send({ outcome: 'DONE', summary: 'Plan verified', resultRef: 'PLAN-DEMO' });
}
else if (mode === 'claude-error') process.stdout.write(JSON.stringify({ type: 'result', subtype: 'error_max_turns', is_error: true }) + '\n');
else send({ outcome: 'DONE', summary: 'Finished', resultRef: 'PLAN-1' });
