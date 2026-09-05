#!/usr/bin/env node
/**
 * Mulino Coreano ERP — MCP Server
 *
 * Exposes the interface mechanism (ASK / ACT / MONITOR) as MCP tools so
 * ChatGPT, Claude Desktop, or any MCP client can query ERP state, create
 * Cases, and inspect attention items over a single, durable business surface.
 */
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { randomUUID } from "node:crypto";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

const CASE_STATUSES = new Set(["OPEN", "IN_PROGRESS", "WAITING", "RESOLVED", "CLOSED"]);
const CHANNELS = new Set(["CHAT", "SLACK", "EMAIL", "DASHBOARD", "API"]);

function caseRequestKey(value) {
  if (value === undefined) return randomUUID();
  if (typeof value !== "string" || value.length === 0 || value.length > 200
    || value.trim() !== value || !/^[\x20-\x7e]+$/.test(value)) {
    throw new Error("requestKey는 공백 없이 시작·종료하는 1~200자의 ASCII 요청 키여야 합니다.");
  }
  return value;
}

function isPositiveIdentifier(value) {
  if (typeof value === "number") return Number.isSafeInteger(value) && value > 0;
  // Keep large identifiers as exact digit strings; never pass them through Number.
  return typeof value === "string" && /^[1-9]\d{0,18}$/.test(value)
    && (value.length < 19 || value <= "9223372036854775807");
}

function casePayload(args) {
  if (Object.keys(args).some(key => !["objective", "channel", "requestKey", "replenishment"].includes(key))) {
    throw new Error("지원되지 않는 create_case 입력입니다.");
  }
  if (typeof args.objective !== "string" || !args.objective.trim()) throw new Error("objective는 비어 있지 않은 목표여야 합니다.");
  if (args.channel !== undefined && !CHANNELS.has(args.channel)) throw new Error("channel이 올바르지 않습니다.");
  const payload = { objective: args.objective, channel: args.channel ?? "CHAT" };
  if (args.replenishment === undefined) return payload;
  const target = args.replenishment;
  if (target === null || typeof target !== "object" || Array.isArray(target)
    || Object.keys(target).some(key => !["productSkus", "warehouseId", "targetDate"].includes(key))
    || !Array.isArray(target.productSkus) || target.productSkus.length < 1 || target.productSkus.length > 100
    || target.productSkus.some(sku => typeof sku !== "string" || !sku.trim() || sku.length > 50)) {
    throw new Error("replenishment.productSkus에는 명시적인 SKU를 1~100개, 각 1~50자로 입력해야 합니다.");
  }
  if (target.warehouseId !== undefined && !isPositiveIdentifier(target.warehouseId)) {
    throw new Error("warehouseId는 양의 정수여야 합니다. 큰 ID는 정확한 숫자 문자열로 전달하세요.");
  }
  if (target.targetDate !== undefined) {
    const date = target.targetDate;
    if (typeof date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(date)
      || !Number.isFinite(Date.parse(date + "T00:00:00Z"))
      || new Date(date + "T00:00:00Z").toISOString().slice(0, 10) !== date) {
      throw new Error("targetDate는 실제 달력 날짜인 YYYY-MM-DD 형식이어야 합니다.");
    }
  }
  payload.replenishment = { productSkus: target.productSkus,
    ...(target.warehouseId !== undefined ? { warehouseId: target.warehouseId } : {}),
    ...(target.targetDate !== undefined ? { targetDate: target.targetDate } : {}) };
  return payload;
}

export function scopeForTool(name) {
  return name === "create_case" ? "work:write" : "erp:read";
}

export function createToolServer(api) {
  const server = new Server(
    {
      name: "mulino-erp",
      version: "0.1.0",
    },
    {
      capabilities: {
        tools: {},
      },
    }
  );

  server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: [
      {
        name: "whoami",
        description: "현재 인증된 인간 사용자와 ERP 역할 및 허용 capability를 확인합니다.",
        inputSchema: { type: "object", properties: {}, additionalProperties: false },
      },
      {
        name: "ask_inventory",
        description:
          "ASK mode — search finished-goods stock by an explicit product name or SKU. Extract only that product/SKU from the conversation. Omit it only when the user explicitly asks for all inventory. This does NOT create a Case.",
        inputSchema: {
          type: "object",
          properties: {
            productQuery: {
              type: "string",
              minLength: 1,
              description: "Product name or SKU search term only (e.g. 'Amaretti' or 'AMR-200'). Omit for an explicit all-inventory query.",
            },
          },
        },
      },
      {
        name: "create_case",
        description:
          "ACT mode — accept an explicit business objective into a persistent Case and queue Orchestrator work. For replenishment, extract only explicitly stated product SKUs into replenishment.productSkus; do not invent SKUs, warehouse IDs, or dates. An overlapping active scope may connect to an existing Case. Preserve requestKey and the same inputs for a caller-directed retry; never retry an ambiguous timeout automatically. Authentication determines the human actor and ERP role.",
        inputSchema: {
          type: "object",
          properties: {
            objective: { type: "string", minLength: 1, description: "Business objective, e.g. '10월 이전 Amaretti 품절 방지'" },
            channel: {
              type: "string",
              enum: ["CHAT", "SLACK", "EMAIL", "DASHBOARD", "API"],
              description: "Entry channel (defaults to CHAT)",
            },
            requestKey: {
              type: "string", minLength: 1, maxLength: 200,
              description: "Stable ASCII idempotency key for this exact request. Reuse the same key and inputs only for a caller-directed retry. If omitted, one UUID is generated and returned in structuredContent.requestKey, including API errors.",
            },
            replenishment: {
              type: "object", additionalProperties: false, required: ["productSkus"],
              description: "Optional replenishment scope. Include when product SKUs were explicitly supplied; do not guess a SKU from a product name.",
              properties: {
                productSkus: {
                  type: "array", minItems: 1, maxItems: 100,
                  items: { type: "string", minLength: 1, maxLength: 50 },
                  description: "Explicit product SKUs from the request, e.g. ['AMR-200'].",
                },
                warehouseId: {
                  anyOf: [
                    { type: "integer", minimum: 1, maximum: Number.MAX_SAFE_INTEGER },
                    { type: "string", pattern: "^[1-9][0-9]{0,18}$" },
                  ],
                  description: "Explicit warehouse ID. Omit to let the backend resolve its sole planning policy; keep large IDs as exact digit strings.",
                },
                targetDate: {
                  type: "string", format: "date", pattern: "^\\d{4}-\\d{2}-\\d{2}$",
                  description: "Explicit target date in YYYY-MM-DD. Omit to use the backend planning policy.",
                },
              },
            },
          },
          required: ["objective"],
          additionalProperties: false,
        },
      },
      {
        name: "list_cases",
        description: "List persistent business Cases (MONITOR).",
        inputSchema: {
          type: "object",
          properties: {
            status: { type: "string", enum: ["OPEN", "IN_PROGRESS", "WAITING", "RESOLVED", "CLOSED"] },
          },
        },
      },
      {
        name: "list_attention",
        description: "Show items that need human attention (AUTHORITY_REQUIRED / JUDGMENT_REQUIRED / etc.).",
        inputSchema: { type: "object", properties: {} },
      },
      {
        name: "monitor_status",
        description: "One-shot ops summary: open cases, at-risk, ready/waiting work items, open attention requests.",
        inputSchema: { type: "object", properties: {} },
      },
    ].map((tool) => ({
      ...tool,
      annotations: { readOnlyHint: tool.name !== "create_case", openWorldHint: false },
      _meta: { securitySchemes: [{ type: "oauth2", scopes: [scopeForTool(tool.name)] }] },
    })),
  }));

  server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: suppliedArguments } = request.params;
    const args = suppliedArguments ?? {};
    let requestKey;
    try {
      switch (name) {
        case "whoami": {
          const data = await api("/me");
          return {
            content: [{ type: "text", text: "사용자: " + data.name + "\n역할: " + data.role }],
            structuredContent: data,
          };
        }
        case "ask_inventory": {
          const query = args?.productQuery?.trim();
          const data = await api(query ? "/ask?q=" + encodeURIComponent(query) : "/ask");
          return {
            content: [
              {
                type: "text",
                text:
                  data.answer +
                  "\n\n출처: " +
                  data.provenance +
                  (data.inventory.length
                    ? "\n\n목록:\n" +
                      data.inventory
                        .map((i) => "- " + i.productName + " (" + i.sku + ") — " + i.quantity + " @ " + i.warehouseName)
                        .join("\n")
                    : ""),
              },
            ],
            structuredContent: data,
          };
        }
        case "create_case": {
          requestKey = caseRequestKey(args.requestKey);
          const payload = casePayload(args);
          const data = await api("/cases", {
            method: "POST",
            headers: { "Content-Type": "application/json", "Idempotency-Key": requestKey },
            body: JSON.stringify(payload),
          });
          return {
            content: [
              {
                type: "text",
                text: (data.reused === true ? "기존 Case에 연결됨: " : "Case 접수됨: ")
                  + data.caseRef + "\n제목: " + data.title + "\n상태: " + data.status
                  + (data.reused === true ? "" : "\nOrchestrator 실행이 예약되었습니다."),
              },
            ],
            structuredContent: { ...data, requestKey },
          };
        }
        case "list_cases": {
          if (args.status && !CASE_STATUSES.has(args.status)) {
            throw new Error("status must be OPEN, IN_PROGRESS, WAITING, RESOLVED, or CLOSED");
          }
          const q = args.status ? "?status=" + encodeURIComponent(args.status) : "";
          const data = await api("/cases" + q);
          return {
            content: [
              {
                type: "text",
                text: data.length
                  ? data.map((c) => c.caseRef + " — [" + c.status + "] " + c.title).join("\n")
                  : "등록된 Case가 없습니다.",
              },
            ],
            structuredContent: { cases: data },
          };
        }
        case "list_attention": {
          const data = await api("/attention");
          return {
            content: [
              {
                type: "text",
                text: data.length
                  ? data
                      .map(
                        (a) =>
                          "[" + a.reasonType + "] " + a.title + " (" + a.caseRef + ")\n  질문: " + a.question +
                          (a.consequence ? "\n  미조치 시: " + a.consequence : "")
                      )
                      .join("\n\n")
                  : "현재 대기 중인 인간 주의 요청이 없습니다.",
              },
            ],
            structuredContent: { attention: data },
          };
        }
        case "monitor_status": {
          const data = await api("/monitor");
          return {
            content: [
              {
                type: "text",
                text:
                  "Case (열림/진행): " + data.casesOpen +
                  "\nCase (위험): " + data.casesAtRisk +
                  "\nWork Item (READY): " + data.workItemsReady +
                  "\nWork Item (WAITING): " + data.workItemsWaiting +
                  "\n주의 요청 (OPEN): " + data.attentionOpen,
              },
            ],
            structuredContent: data,
          };
        }
        default:
          throw new Error("Unknown tool: " + name);
      }
    } catch (e) {
      return {
        content: [{ type: "text", text: "오류: " + e.message }],
        isError: true,
        ...(requestKey ? { structuredContent: { requestKey } } : {}),
      };
    }
  });

  return server;
}
