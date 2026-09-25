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
    structured_output: { outcome: 'DONE', summary: JSON.parse(input).context.objective, resultRef: 'PLAN-7' } }]
  .map(event => JSON.stringify(event)).join('\n') + '\n');
else if (mode === 'claude-error') process.stdout.write(JSON.stringify({ type: 'result', subtype: 'error_max_turns', is_error: true }) + '\n');
else send({ outcome: 'DONE', summary: 'Finished', resultRef: 'PLAN-1' });
