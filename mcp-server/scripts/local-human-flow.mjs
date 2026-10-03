// Opt-in business SIT against a running local backend with a prepared purchase proposal.
// No worker secret or capability is accepted by this process.
import assert from 'node:assert/strict';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const base=process.env.MULINO_API_BASE;
const approvalId=process.env.MULINO_TEST_APPROVAL_ID;
const planRef=process.env.MULINO_TEST_PLAN_REF;
const caseRef=process.env.MULINO_TEST_CASE_REF;
const attentionId=process.env.MULINO_TEST_ATTENTION_ID;
assert.ok(base && approvalId && planRef && caseRef && attentionId,'Prepared local fixture references are required');
const clients=[];
async function client(role) {
  const c=new Client({name:'local-human-sit',version:'1.0.0'});
  await c.connect(new StdioClientTransport({command:process.execPath,args:['src/index.js'],cwd:path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),env:{PATH:process.env.PATH,MULINO_API_BASE:base,MULINO_LOCAL_ROLE:role}}));
  clients.push(c);return c;
}
async function tool(c,name,args={}) {
  const r=await c.callTool({name,arguments:args});assert.notEqual(r.isError,true,JSON.stringify(r.content));return r.structuredContent;
}
try {
  for(const route of [`/approvals/${approvalId}`,`/purchase-orders/1`])
    assert.equal((await fetch(base+route)).status,401);
  const viewer=await client('VIEWER');
  assert.equal((await tool(viewer,'whoami')).role,'VIEWER');
  assert.equal((await tool(viewer,'get_case',{caseRef})).caseRef,caseRef);
  assert.equal((await tool(viewer,'get_plan',{planRef})).ref,planRef);
  const approval=await tool(viewer,'get_approval',{approvalId});
  assert.equal(approval.status,'PENDING');assert.equal(String(approval.proposal.totalKrw),'16500');
  const args={approvalId,decision:'APPROVE',expectedVersion:approval.version,proposalHash:approval.proposalHash,reason:'Fixture quantities and supplier conditions reviewed',requestKey:'live-purchase-once'};
  assert.equal((await viewer.callTool({name:'decide_purchase',arguments:args})).isError,true);
  assert.equal((await viewer.callTool({name:'answer_attention',arguments:{attentionRequestId:attentionId,answer:'Friday',scope:'THIS_ACTION',expectedVersion:1,requestKey:'viewer-answer'}})).isError,true);
  const manager=await client('MANAGER');
  const approved=await tool(manager,'decide_purchase',args);
  assert.equal(approved.status,'APPROVED');assert.equal(approved.purchaseOrderIds.length,1);
  assert.equal((await tool(viewer,'get_case',{caseRef})).status,'WAITING');
  const replay=await tool(manager,'decide_purchase',args);
  assert.deepEqual(replay.purchaseOrderIds,approved.purchaseOrderIds);
  const po=await tool(viewer,'get_purchase_order',{purchaseOrderId:String(approved.purchaseOrderIds[0])});
  assert.equal(po.status,'ORDERED');const totalKrw=po.items.reduce((sum,line)=>sum+BigInt(line.lineAmountKrw),0n).toString();assert.equal(totalKrw,'16500');assert.equal(po.caseRef,caseRef);
  for(const r of [approval,approved,replay,po]) {
    assert.equal(r.capabilityToken,undefined);assert.equal(r.leaseToken,undefined);
  }
  const operator=await client('OPERATOR');
  const answerArgs={attentionRequestId:attentionId,answer:'Continue only this action',scope:'THIS_ACTION',expectedVersion:1,requestKey:'live-answer-once'};
  const answer=await tool(operator,'answer_attention',answerArgs);
  const sameAnswer=await tool(operator,'answer_attention',answerArgs);
  assert.equal(answer.status,'ANSWERED');assert.equal(answer.version,2);assert.equal(sameAnswer.decisionId,answer.decisionId);
  console.log(JSON.stringify({tools:7,approvalStatus:approved.status,purchaseOrderIds:approved.purchaseOrderIds,totalKrw,answerStatus:answer.status,answerVersion:answer.version,replay:true,viewerWritesDenied:true,anonymousReadsDenied:true}));
} finally {await Promise.all(clients.map(c=>c.close()));}
