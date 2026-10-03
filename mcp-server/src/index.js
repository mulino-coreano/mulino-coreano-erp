#!/usr/bin/env node
/**
 * Mulino Coreano ERP — MCP Server
 *
 * Exposes the interface mechanism (ASK / ACT / MONITOR) as MCP tools so
 * ChatGPT, Claude Desktop, or any MCP client can query ERP state, create
 * Cases, and inspect attention items over a single, durable business surface.
 */
import { randomUUID } from "node:crypto";
import { conversationTools, callConversationTool } from "./human-tools.js";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

const BASE = (process.env.MULINO_API_BASE ?? "http://localhost:8080/api/v1").replace(/\/$/, "");
const configuredTimeout = Number(process.env.MULINO_API_TIMEOUT_MS ?? "10000");
const API_TIMEOUT_MS = Number.isFinite(configuredTimeout) && configuredTimeout > 0
  ? configuredTimeout
  : 10000;
const CASE_STATUSES = new Set(["OPEN", "IN_PROGRESS", "WAITING", "RESOLVED", "CLOSED"]);

async function api(path, opts = {}) {
  const method = (opts.method ?? "GET").toUpperCase();
  try {
    const res = await fetch(BASE + path, {
      ...opts,
      signal: AbortSignal.timeout(API_TIMEOUT_MS),
    });
    if (!res.ok) throw new Error("API " + res.status + ": " + (await res.text()));
    return JSON.parse(await res.text(), (_key, value, context) => {
      if (typeof value !== "number") return value;
      if (typeof context?.source !== "string") throw new Error("Exact JSON numbers require Node 22 or newer");
      return /[.eE]/.test(context.source) || !Number.isSafeInteger(value) ? context.source : value;
    });
  } catch (error) {
    if (error?.name === "TimeoutError" || error?.name === "AbortError") {
      const uncertain = !["GET", "HEAD", "OPTIONS"].includes(method)
        ? " 서버에서 요청이 반영되었을 수 있습니다. 자동 재시도하지 말고 현재 상태를 먼저 확인하세요."
        : "";
      throw new Error(`API 요청 시간 초과 (${API_TIMEOUT_MS}ms).${uncertain}`);
    }
    throw error;
  }
}

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
    ...conversationTools.map(({name, description, inputSchema, write}) => ({name, description, inputSchema, annotations: {readOnlyHint: !write, destructiveHint: !!write, openWorldHint: false}})),
    { name: "whoami", description: "현재 로컬 인간 역할을 조회한다.", inputSchema: {type: "object", properties: {}} },
    { name: "get_case", description: "Case 참조로 업무를 조회한다.", inputSchema: {type: "object", properties: {caseRef: {type: "string", minLength: 1}}, required: ["caseRef"]} },
    { name: "get_plan", description: "저장된 재보충 계획과 근거를 조회한다.", inputSchema: {type: "object", properties: {planRef: {type: "string", minLength: 1}}, required: ["planRef"]} },
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
        "ACT mode — give the organization an objective and create a persistent Case. Orchestrator agent is attached and an initial Work Item is seeded.",
      inputSchema: {
        type: "object",
        properties: {
          objective: { type: "string", description: "Business objective, e.g. '10월 이전 Amaretti 품절 방지'" },
          channel: {
            type: "string",
            enum: ["CHAT", "SLACK", "EMAIL", "DASHBOARD", "API"],
            description: "Entry channel (defaults to CHAT)",
          },
        },
        required: ["objective"],
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
  ],
}));

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: suppliedArguments } = request.params;
  const args = suppliedArguments ?? {};
  let requestKey;
  try {
    const humanTool = conversationTools.find(tool => tool.name === name);
    if (humanTool) {
      if (humanTool.write) {
        requestKey = args.requestKey ?? randomUUID();
        if (typeof requestKey !== "string" || !requestKey.trim() || requestKey.length > 200) throw new Error("Invalid requestKey");
      }
      return await callConversationTool(humanTool, args, (path, opts = {}) => api(path, {...opts, headers: {...opts.headers, "X-Mulino-Local-Role": process.env.MULINO_LOCAL_ROLE ?? "OPERATOR"}}), requestKey);
    }
    switch (name) {
      case "whoami":
      case "get_case":
      case "get_plan": {
        const ref = name === "get_case" ? args.caseRef : args.planRef;
        if (name !== "whoami" && (typeof ref !== "string" || !ref.trim())) throw new Error("참조가 필요합니다.");
        const path = name === "whoami" ? "/me" : (name === "get_case" ? "/cases/" : "/plans/") + encodeURIComponent(ref);
        const data = await api(path, {headers: {"X-Mulino-Local-Role": process.env.MULINO_LOCAL_ROLE ?? "OPERATOR"}});
        return {content: [{type: "text", text: JSON.stringify(data)}], structuredContent: data};
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
        const data = await api("/cases", {
          method: "POST",
          headers: { "Content-Type": "application/json",
            "X-Mulino-Local-Role": process.env.MULINO_LOCAL_ROLE ?? "OPERATOR" },
          body: JSON.stringify({ objective: args.objective, channel: args.channel ?? "CHAT" }),
        });
        return {
          content: [
            {
              type: "text",
              text: "Case 생성됨: " + data.caseRef + "\n제목: " + data.title + "\n상태: " + data.status,
            },
          ],
          structuredContent: data,
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
                        "요청 " + a.attentionRequestId + " / 버전 " + a.version + (a.governanceActionId == null ? " (answer_attention)" : " / 구매 승인 " + a.governanceActionId + " (get_approval → decide_purchase)") + "\n[" + a.reasonType + "] " + a.title + " (" + a.caseRef + ")\n  질문: " + a.question +
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
      ...(requestKey ? {structuredContent: {requestKey}} : {}),
    };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
console.error("mulino-erp MCP server running (base=" + BASE + ")");
