// Human conversation surface: read projections and explicit, version-bound decisions.
export const identifierSchema = { anyOf: [
  { type: "integer", minimum: 1, maximum: Number.MAX_SAFE_INTEGER },
  { type: "string", pattern: "^[1-9][0-9]{0,18}$" },
] };
const refSchema = { type: "string", minLength: 1, maxLength: 100 };
const versionSchema = { type: "integer", minimum: 1, maximum: 2147483647 };
const keySchema = { type: "string", minLength: 1, maxLength: 200,
  description: "동일 입력의 사용자 지시 재시도에만 재사용. 생략 시 생성한 UUID를 오류에도 반환합니다. 자동 재시도 금지." };
const schema = (properties, required = Object.keys(properties)) => ({ type: "object", properties, required, additionalProperties: false });
const read = (name, description, field, path, format, id = false) => ({ name, description,
  inputSchema: schema({ [field]: id ? identifierSchema : refSchema }), scope: "erp:read", path, format,
  validate(args) { if (id) requireIdentifier(args[field], field); else {
    requireText(args[field], field, 100);
    if ([".", ".."].includes(args[field])) throw new Error(`${field}에는 경로의 점 구간을 사용할 수 없습니다.`);
  } },
});
export function requireIdentifier(value, field) {
  if (!(typeof value === "number" ? Number.isSafeInteger(value) && value > 0
    : typeof value === "string" && /^[1-9]\d{0,18}$/.test(value)
      && (value.length < 19 || value <= "9223372036854775807"))) {
    throw new Error(`${field}는 양의 정수 또는 signed 64-bit 범위의 정확한 숫자 문자열이어야 합니다.`);
  }
}
function requireText(value, field, max) {
  if (typeof value !== "string" || !value.trim() || value.length > max) throw new Error(`${field}는 비어 있지 않은 ${max}자 이하 문자열이어야 합니다.`);
}
function requireVersion(value) {
  if (!Number.isInteger(value) || value < 1 || value > 2147483647) throw new Error("expectedVersion은 1~2147483647 정수여야 합니다.");
}
export function rejectUnknown(args, allowed) {
  if (Object.keys(args).some(key => !allowed.includes(key))) throw new Error("지원되지 않는 입력입니다. 사용자와 권한은 인증으로 결정됩니다.");
}
const show = value => value === null || value === undefined ? "미제공" : typeof value === "object" ? JSON.stringify(value) : String(value);
const rows = values => (values ?? []).map(value => `- ${show(value)}`).join("\n") || "없음";
const lineText = line => `${show(line.materialName)} (자재 ${show(line.materialId)}, 계약 ${show(line.sourceTermId)}): ${show(line.buyQuantity)} ${show(line.buyUnit)} × ${show(line.buyUnitPrice)}원 = ${show(line.lineAmountKrw)}원; 기준 ${show(line.baseQuantity)} ${show(line.baseUnit)}, 환산 ${show(line.baseUnitsPerBuyUnit)}, 기준단가 ${show(line.baseUnitPrice)}원; 납기 ${show(line.expectedDeliveryDate)}`;
export function approvalText(data) {
  if (data.error) return `구매 결정 미완료: ${show(data.error)}\n현재 승인안을 다시 조회하고 변경된 버전과 내용을 확인하세요.`;
  const proposal = data.proposal;
  return `승인 ${show(data.ref ?? data.id)}: ${show(data.status)}\nCase: ${show(data.caseRef)} / 계획: ${show(data.planRef)}\n필요 권한: ${show(data.requiredRole)} (구매 결정: MANAGER)\n제안 버전: ${show(data.version)}\n제안 해시: ${show(data.proposalHash)}\n만료: ${show(data.expiresAt)}\n`
    + (proposal ? `창고: ${show(proposal.warehouseId)} / 목표일: ${show(proposal.targetDate)}\n${(proposal.orders ?? []).map(order => `공급사: ${show(order.supplierName)} (${show(order.supplierId)}), 통화 ${show(order.currency)}, 납기 ${show(order.expectedDeliveryDate)}\n${(order.lines ?? []).map(lineText).join("\n")}\n공급사 합계: ${show(order.totalKrw)}원`).join("\n")}\n총액: ${show(proposal.totalKrw)}원` : "제안 근거 미제공")
    + `\n최종 결정: ${show(data.decision)}\n발주 ID: ${show(data.purchaseOrderIds)}\n다음 행동: 대기 중이면 표시된 버전·해시와 구매 내용을 확인한 인간의 명시적 승인 또는 차단 선택이 필요합니다. 생산·입고의 이행 여부는 별도로 확인해야 합니다.`;
}
function caseText(data) {
  const summary = data.summary ?? {};
  return `Case ${show(data.case?.caseRef)}: ${show(data.case?.title)}\n상태: ${show(summary.state)} / 인간 응답 필요: ${show(summary.needsHumanAttention)}\n진행 작업: ${show(summary.activeWorkCount)} / 남은 작업: ${show(summary.remainingWorkCount)}\n참여자:\n${rows((data.participants ?? []).map(p => `${show(p.userName ?? p.name ?? p.agentName)} / 인간 ${show(p.userId ?? p.assignedUserId)} / 에이전트 ${show(p.agentName ?? p.agentKey)}`))}\n작업과 대기:\n${rows((data.workItems ?? []).map(w => `${show(w.workItemRef)} ${show(w.title)}: ${show(w.status)} / 인간 ${show(w.userName)} / 에이전트 ${show(w.agentName)} / 활성 대기: ${show(w.activeWaits)}`))}\n인간 주의 요청:\n${rows((data.attention ?? []).map(a => `요청 ${show(a.attentionRequestId)} / 버전 ${show(a.version)} / ${show(a.status)}: ${show(a.question)} / 구매 승인 ${show(a.governanceActionId)}`))}\n승인:\n${rows((data.approvals ?? []).map(a => `${show(a.ref ?? a.id ?? a.governanceActionId)}: ${show(a.status)} / 필요 권한 ${show(a.requiredRole)} / 버전 ${show(a.proposalVersion ?? a.version)} / 발주 ${show(a.purchaseOrders ?? a.purchaseOrderIds)}`))}\n근거:\n${rows(data.evidence)}\n남은 의무:\n${rows(data.remainingObligations)}\n다음 행동:\n${rows(summary.nextActions)}\n예약·대기 작업은 완료를 뜻하지 않습니다. 구매 승인에 연결된 주의 요청은 get_approval 확인 후 decide_purchase로 결정하세요.`;
}
export const conversationTools = [
  read("get_case", "Case의 목표, 인간·에이전트 참여자, 작업·대기, 근거, 승인 및 남은 의무를 조회합니다. 조회는 실행을 시작하지 않습니다.", "caseRef", a => `/cases/${encodeURIComponent(a.caseRef)}/overview`, caseText),
  read("get_plan", "계획의 불변 버전, 원본 근거와 계산 결과를 조회합니다. 금액을 다시 계산하거나 작업을 실행하지 않습니다.", "planRef", a => `/plans/${encodeURIComponent(a.planRef)}`, d => `계획 ${show(d.ref)} / Case ${show(d.caseRef)}\n버전: ${show(d.version)} / 기준 시각: ${show(d.asOf)} / 목표일: ${show(d.targetDate)}\n원본 해시: ${show(d.sourceHash)}\n계획 해시: ${show(d.hash)}\n계산 결과: ${show(d.result)}\n주의 요청: ${show(d.attention)}\n다음 행동: 구매가 필요하면 Case의 승인안을 확인하세요. 계획은 발주·생산·입고 완료를 뜻하지 않습니다.`),
  read("get_approval", "구매 승인안의 정확한 공급사별 품목·수량·단가·합계, 버전·해시와 MANAGER 권한을 확인합니다.", "approvalId", a => `/approvals/${a.approvalId}`, approvalText, true),
  read("get_purchase_order", "실제 발주 상태와 품목, 수량·단가, 입고 수량 및 승인 근거를 조회합니다.", "purchaseOrderId", a => `/purchase-orders/${a.purchaseOrderId}`, d => `발주 ${show(d.id)}: ${show(d.status)}\n공급사: ${show(d.supplierName)} (${show(d.supplierId)})\nCase: ${show(d.caseRef)} / 계획: ${show(d.planRef)} / 승인: ${show(d.approvalId)}\n${(d.items ?? []).map(line => `${lineText(line)}; 입고 수량 ${show(line.receivedBaseQuantity)} ${show(line.baseUnit)}`).join("\n")}\n다음 행동: 납기·입고와 생산 이행을 별도 확인하세요.`, true),
  { name: "decide_purchase", scope: "procurement:decide", write: true,
    description: "MANAGER의 구매 승인 또는 차단 결정을 기록합니다. get_approval로 표시한 정확한 구매 내용·버전·해시에 대해 매번 인간의 명시적 선택을 받은 뒤만 호출하세요. 저장된 정책·과거 동의로 자동 승인하지 마세요. 서버는 클라이언트의 확인 절차를 증명하지 못합니다. APPROVE는 실제 발주를 생성할 수 있습니다. 자동 재시도 금지.",
    inputSchema: schema({ approvalId: identifierSchema, decision: { type: "string", enum: ["APPROVE", "BLOCK"] }, expectedVersion: versionSchema, proposalHash: { type: "string", pattern: "^[0-9a-f]{64}$" }, reason: { type: "string", minLength: 1, maxLength: 4000 }, requestKey: keySchema }, ["approvalId", "decision", "expectedVersion", "proposalHash", "reason"]),
    validate(a) { requireIdentifier(a.approvalId, "approvalId"); requireVersion(a.expectedVersion); requireText(a.reason, "reason", 4000); if (!["APPROVE", "BLOCK"].includes(a.decision)) throw new Error("decision은 APPROVE 또는 BLOCK이어야 합니다."); if (typeof a.proposalHash !== "string" || !/^[0-9a-f]{64}$/.test(a.proposalHash)) throw new Error("proposalHash는 소문자 64자리 SHA-256 해시여야 합니다."); },
    path: a => `/approvals/${a.approvalId}/decision`, body: ({ decision, expectedVersion, proposalHash, reason }) => ({ decision, expectedVersion, proposalHash, reason }), format: approvalText },
  { name: "answer_attention", scope: "work:write", write: true,
    description: "인간에게 요청된 일반 질문에 명시적 답변을 기록합니다. 최신 attentionRequestId·version과 답변 적용 범위를 확인하세요. governanceActionId가 있는 구매 승인 요청은 이 도구로 답하지 말고 get_approval 및 decide_purchase를 사용하세요. THIS_CASE는 답변 범위이며 향후 구매 자동 승인 권한이 아닙니다. 작업 재개 예약은 완료가 아닙니다. 자동 재시도 금지.",
    inputSchema: schema({ attentionRequestId: identifierSchema, answer: { type: "string", minLength: 1, maxLength: 8000 }, expectedVersion: versionSchema, scope: { type: "string", enum: ["THIS_ACTION", "THIS_CASE"] }, requestKey: keySchema }, ["attentionRequestId", "answer", "expectedVersion", "scope"]),
    validate(a) { requireIdentifier(a.attentionRequestId, "attentionRequestId"); requireText(a.answer, "answer", 8000); requireVersion(a.expectedVersion); if (!["THIS_ACTION", "THIS_CASE"].includes(a.scope)) throw new Error("scope는 THIS_ACTION 또는 THIS_CASE여야 합니다."); },
    path: a => `/attention/${a.attentionRequestId}/answer`, body: ({ answer, expectedVersion, scope }) => ({ answer, expectedVersion, scope }),
    format: d => `주의 요청 ${show(d.attentionRequestId)}: ${show(d.status)}\nCase: ${show(d.caseRef)} / 작업: ${show(d.workItemRef)}\n답변: ${show(d.answer)}\n범위: ${show(d.scope)} / 버전: ${show(d.version)}\n재개: ${show(d.resume)}\n다음 행동: get_case로 작업 진행과 남은 의무를 확인하세요. 재개 예약은 업무 완료를 뜻하지 않습니다.` },
];
export async function callConversationTool(tool, args, api, requestKey) {
  rejectUnknown(args, Object.keys(tool.inputSchema.properties));
  tool.validate(args);
  const data = await api(tool.path(args), tool.write ? { method: "POST", headers: { "Content-Type": "application/json", "Idempotency-Key": requestKey }, body: JSON.stringify(tool.body(args)) } : undefined);
  return { content: [{ type: "text", text: tool.format(data) }], structuredContent: tool.write ? { ...data, requestKey } : data, ...(data.error ? { isError: true } : {}) };
}
