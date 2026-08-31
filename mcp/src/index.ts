#!/usr/bin/env node
/**
 * MCP server for Ossian.
 *
 * Gives an agent two things it cannot get from a model alone: answers grounded in a company's own
 * documents, with the passage that produced each one, and a memory that survives the
 * conversation.
 *
 * The tool descriptions are unusually explicit about *when not* to use each one. A tool
 * description is a prompt — it is the only thing the model reads before deciding — and the common
 * failure is not a malformed call but a well-formed call to the wrong tool. `ask` searching a
 * corpus that cannot answer, when the agent should have said it did not know, is worse than no
 * answer at all.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { OssianClient, OssianError } from "./client.js";

const baseUrl = (process.env.OSSIAN_URL ?? "http://localhost:8081").replace(/\/$/, "");
const apiKey = process.env.OSSIAN_API_KEY ?? "";
const defaultAgentId = process.env.OSSIAN_AGENT_ID ?? "mcp";

if (!apiKey) {
  // Fail at startup with the fix, rather than on the first tool call with a 401 the model will
  // try to work around.
  process.stderr.write(
    "OSSIAN_API_KEY is not set. Issue one from the Ossian console (Console → API keys) or:\n" +
      "  curl -X POST $OSSIAN_URL/api/admin/api-keys -H 'Authorization: Bearer <token>' \\\n" +
      "    -H 'Content-Type: application/json' -d '{\"name\":\"mcp\",\"roles\":[\"ossian-user\"]}'\n",
  );
  process.exit(1);
}

const ossian = new OssianClient(baseUrl, apiKey);
const server = new McpServer({ name: "ossian", version: "0.1.0" });

/** Errors reach the model as text it can act on, never as a stack trace. */
function fail(err: unknown) {
  const message =
    err instanceof OssianError
      ? err.status === 401 || err.status === 403
        ? `Ossian refused this request (${err.status}): ${err.message}. The API key may lack the role, or be confined to another namespace.`
        : err.message
      : err instanceof Error
        ? err.message
        : String(err);
  return { content: [{ type: "text" as const, text: message }], isError: true };
}

const text = (value: string) => ({ content: [{ type: "text" as const, text: value }] });

server.tool(
  "ask_documents",
  "Answer a question from the organisation's own documents, with citations. Returns the exact " +
    "passages the answer was written from. Use this for anything about internal policy, runbooks, " +
    "handbooks, contracts or product documentation — anything the user's organisation wrote down. " +
    "If nothing in the corpus supports an answer this reports that rather than guessing: treat " +
    "'not found' as the real answer and say so, do not fall back on general knowledge and present " +
    "it as the organisation's policy.",
  {
    question: z.string().min(1).describe("The question, in full. Prefer the user's own wording."),
    namespace: z
      .string()
      .optional()
      .describe("Narrow to one slice of the corpus, e.g. 'runbooks'. Omit to search everything."),
  },
  async ({ question, namespace }) => {
    try {
      const r = await ossian.ask(question, namespace);
      if (!r.answeredFromContext) {
        return text(
          `No supporting passage was found in the documents.\n\n${r.answer}\n\n` +
            `Report this as "not covered by the documents" rather than answering from general knowledge.`,
        );
      }
      const sources = r.citations
        .map((c) => `[${c.index}] ${c.filename}${c.score != null ? ` (similarity ${c.score.toFixed(2)})` : ""}\n${c.excerpt}`)
        .join("\n\n");
      return text(`${r.answer}\n\n--- sources ---\n${sources}`);
    } catch (err) {
      return fail(err);
    }
  },
);

server.tool(
  "list_namespaces",
  "List the slices the document corpus is divided into, with how many documents each holds. Use " +
    "this before ask_documents when a question clearly belongs to one area and you want to avoid " +
    "another area answering it.",
  {},
  async () => {
    try {
      const ns = await ossian.namespaces();
      if (!ns.length) return text("No namespaces exist yet.");
      return text(ns.map((n) => `${n.name} — ${n.documents} documents, ${n.chunks} passages`).join("\n"));
    } catch (err) {
      return fail(err);
    }
  },
);

server.tool(
  "list_documents",
  "List the documents available to answer from, with their ingestion status. Use this to tell the " +
    "user what the system actually knows about, or to check whether something they uploaded has " +
    "finished processing.",
  { namespace: z.string().optional().describe("Restrict to one namespace.") },
  async ({ namespace }) => {
    try {
      const page = await ossian.documents(namespace);
      if (!page.totalElements) return text("No documents.");
      const rows = page.content
        .map((d) => `${d.filename} — ${String(d.status).toLowerCase()}, ${d.chunkCount} passages [${d.namespace}]`)
        .join("\n");
      return text(`${page.totalElements} documents\n\n${rows}`);
    } catch (err) {
      return fail(err);
    }
  },
);

server.tool(
  "add_document_from_url",
  "Fetch a public web page and add it to the corpus so it can be answered from later. Ingestion " +
    "is asynchronous — the document is not answerable the instant this returns. The server refuses " +
    "private and internal addresses, so this cannot be used to reach anything on its network.",
  {
    url: z.string().url().describe("A public http or https URL."),
    namespace: z.string().optional().describe("Which slice to file it under."),
    title: z.string().optional().describe("A title, if the page's own is unhelpful."),
  },
  async ({ url, namespace, title }) => {
    try {
      const r = await ossian.addUrl(url, namespace, title);
      return text(
        r.duplicate
          ? `Already in the corpus (identical content): ${r.documentId}`
          : `Ingesting ${url} as ${r.documentId}. It becomes answerable once processing finishes.`,
      );
    } catch (err) {
      return fail(err);
    }
  },
);

server.tool(
  "remember",
  "Record something worth carrying beyond this conversation — a stated preference, a durable fact " +
    "about the user, a decision and its reason. Do NOT use this for things the documents already " +
    "say, for anything the user would be surprised to find stored, or for the content of the " +
    "conversation itself. Restating something already remembered is harmless: it updates rather " +
    "than duplicating.",
  {
    content: z.string().min(1).describe("One self-contained statement. It will be read back without surrounding context."),
    subject: z.string().optional().describe("Who or what it is about, e.g. 'user:ankit'. Enables recalling everything known about them."),
    kind: z.enum(["fact", "preference", "decision", "event"]).optional().describe("Defaults to fact."),
    importance: z.number().min(0).max(5).optional().describe("Above 1 makes it outrank equally similar memories. Use sparingly."),
    sessionId: z.string().optional().describe("Scopes it to one conversation. Pair with ttlSeconds for scratch notes."),
    ttlSeconds: z.number().int().positive().optional().describe("Forget after this long. Omit to keep indefinitely."),
    agentId: z.string().optional(),
  },
  async ({ content, subject, kind, importance, sessionId, ttlSeconds, agentId }) => {
    try {
      const m = await ossian.remember({
        agentId: agentId ?? defaultAgentId,
        content,
        subject,
        kind: kind ?? "fact",
        importance,
        sessionId,
        ttlSeconds,
      });
      return text(`Remembered (${m.kind}${m.subject ? `, about ${m.subject}` : ""}).`);
    } catch (err) {
      return fail(err);
    }
  },
);

server.tool(
  "recall",
  "Retrieve what was previously remembered that relates to the current situation. Ranked by " +
    "relevance, weighted by importance and decayed by age, so a recent statement outranks an old " +
    "one saying the opposite. Worth calling at the start of a conversation with a returning user. " +
    "This searches memory, not documents — use ask_documents for anything the organisation wrote " +
    "down.",
  {
    query: z.string().min(1).describe("What you are trying to recall, in natural language."),
    subject: z.string().optional().describe("Narrow to one subject, e.g. 'user:ankit'."),
    sessionId: z.string().optional().describe("Narrow to one conversation."),
    limit: z.number().int().min(1).max(50).optional(),
    agentId: z.string().optional(),
  },
  async ({ query, subject, sessionId, limit, agentId }) => {
    try {
      const found = await ossian.recall({
        agentId: agentId ?? defaultAgentId,
        query,
        subject,
        sessionId,
        topK: limit ?? 8,
      });
      if (!found.length) return text("Nothing relevant is remembered.");
      return text(
        found
          .map((m) => `- ${m.content}${m.subject ? `  (${m.subject})` : ""}${m.score != null ? `  [${m.score.toFixed(2)}]` : ""}`)
          .join("\n"),
      );
    } catch (err) {
      return fail(err);
    }
  },
);

server.tool(
  "forget_session",
  "Erase everything remembered for one conversation. Use when a session ends or the user asks to " +
    "be forgotten. Durable memories about a subject are not affected.",
  {
    sessionId: z.string().min(1),
    agentId: z.string().optional(),
  },
  async ({ sessionId, agentId }) => {
    try {
      const r = await ossian.forgetSession(agentId ?? defaultAgentId, sessionId);
      return text(`Forgot ${r.forgotten} ${r.forgotten === 1 ? "memory" : "memories"} from that session.`);
    } catch (err) {
      return fail(err);
    }
  },
);

const transport = new StdioServerTransport();
await server.connect(transport);
// stdout carries the protocol; anything written there that is not a JSON-RPC frame corrupts the
// stream. Diagnostics go to stderr.
process.stderr.write(`ossian-mcp connected to ${baseUrl}\n`);
