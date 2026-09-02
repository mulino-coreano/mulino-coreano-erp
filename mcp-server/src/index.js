#!/usr/bin/env node
/**
 * Mulino Coreano ERP — MCP Server
 *
 * Exposes the interface mechanism (ASK / ACT / MONITOR) as MCP tools so
 * ChatGPT, Claude Desktop, or any MCP client can query ERP state, create
 * Cases, and inspect attention items over a single, durable business surface.
 */
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

const BASE = process.env.MULINO_API_BASE ?? "http://localhost:8080/api/v1";

async function api(path, opts = {}) {
  const res = await fetch(BASE + path, opts);
  if (!res.ok) throw new Error("API " + res.status + ": " + (await res.text()));
  return res.json();
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
    {
      name: "ask_inventory",
      description:
        "ASK mode — query current ERP state (finished-goods stock). This does NOT create a Case; it is a read-only business question.",
      inputSchema: {
        type: "object",
        properties: {
          question: {
            type: "string",
            description: "Natural-language question (e.g. 'Amaretti 재고 얼마나 있어?')",
          },
        },
        required: ["question"],
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
  const { name, arguments: args } = request.params;
  try {
    switch (name) {
      case "ask_inventory": {
        const data = await api("/ask?q=" + encodeURIComponent(args.question));
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
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ objective: args.objective, intentType: "ACT", channel: args.channel ?? "CHAT" }),
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
        const q = args.status ? "?status=" + args.status : "";
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
                "\nCase (대기): " + data.casesAtRisk +
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
    };
  }
});

const transport = new StdioServerTransport();
await server.connect(transport);
console.error("mulino-erp MCP server running (base=" + BASE + ")");
