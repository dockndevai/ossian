import { useState } from "react";
import { useAuth } from "react-oidc-context";

/**
 * A working API console.
 *
 * Swagger UI is already served at /swagger-ui.html and describes the schema better than this
 * could. What it cannot easily do is send a request as *you*: every call here carries the token
 * from the current session, so what you see is what your own roles and tenant actually return.
 * That is the difference between reading that an endpoint is admin-only and watching it 403.
 *
 * The page is routed at /explorer, not /api: the dev server proxies everything under /api to
 * the backend, so a page living there would never be reached by the app that serves it.
 */

interface Endpoint {
  method: "GET" | "POST" | "PUT" | "DELETE";
  path: string;
  summary: string;
  admin?: boolean;
  body?: string;
}

const ENDPOINTS: { group: string; items: Endpoint[] }[] = [
  {
    group: "Chat",
    items: [
      {
        method: "POST",
        path: "/api/chat",
        summary: "Ask a question. Returns the answer with the citations it was grounded in.",
        body: JSON.stringify({ question: "Can we deploy on a Friday?", namespace: "default" }, null, 2),
      },
      {
        method: "POST",
        path: "/api/chat/stream",
        summary: "The same, as server-sent events. Each frame is one JSON-quoted token.",
        body: JSON.stringify({ question: "Can we deploy on a Friday?" }, null, 2),
      },
    ],
  },
  {
    group: "Documents",
    items: [
      { method: "GET", path: "/api/documents", summary: "List documents. Add ?namespace= to narrow." },
      { method: "GET", path: "/api/namespaces", summary: "Namespaces available to your tenant." },
      {
        method: "POST",
        path: "/api/namespaces",
        summary: "Create a namespace. The name is slugified.",
        body: JSON.stringify({ name: "Engineering", description: "Runbooks and handbooks" }, null, 2),
      },
    ],
  },
  {
    group: "Event ingestion",
    items: [
      {
        method: "POST",
        path: "/api/events/documents",
        summary: "Upsert or delete a document by its identity in the source system.",
        body: JSON.stringify(
          {
            eventId: "crm-4172-v3",
            operation: "UPSERT",
            externalId: "crm/4172",
            namespace: "default",
            source: "crm-cdc",
            filename: "record-4172.txt",
            text: "The document body as plain text.",
          },
          null,
          2,
        ),
      },
      {
        method: "POST",
        path: "/api/events/documents/batch",
        summary: "Up to 500 events at once. Reports per event; one bad record does not fail the batch.",
        body: JSON.stringify(
          {
            events: [
              { eventId: "b-1", operation: "UPSERT", externalId: "crm/1", text: "First." },
              { eventId: "b-2", operation: "DELETE", externalId: "crm/2" },
            ],
          },
          null,
          2,
        ),
      },
      { method: "GET", path: "/api/events/documents", summary: "What the pipeline has delivered." },
    ],
  },
  {
    group: "Admin",
    items: [
      { method: "GET", path: "/api/admin/stats/corpus", summary: "Corpus size and health.", admin: true },
      { method: "GET", path: "/api/admin/stats/retrieval", summary: "Answer rate and latency.", admin: true },
      { method: "GET", path: "/api/admin/gaps", summary: "Questions the corpus could not answer.", admin: true },
      { method: "GET", path: "/api/admin/settings", summary: "Effective configuration.", admin: true },
      { method: "GET", path: "/api/admin/vectors/chunks", summary: "Stored chunks and their embeddings.", admin: true },
      {
        method: "POST",
        path: "/api/admin/vectors/search",
        summary: "Nearest chunks to a query, with no model in the loop.",
        admin: true,
        body: JSON.stringify({ query: "how much notice before leave", topK: 5 }, null, 2),
      },
    ],
  },
];

export default function ApiPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [selected, setSelected] = useState<Endpoint>(ENDPOINTS[0].items[0]);
  const [body, setBody] = useState(ENDPOINTS[0].items[0].body ?? "");
  const [response, setResponse] = useState<{ status: number; text: string; ms: number } | null>(null);
  const [busy, setBusy] = useState(false);

  function choose(endpoint: Endpoint) {
    setSelected(endpoint);
    setBody(endpoint.body ?? "");
    setResponse(null);
  }

  async function send() {
    setBusy(true);
    const started = performance.now();
    try {
      const headers: Record<string, string> = { Authorization: `Bearer ${token}` };
      if (selected.method !== "GET" && body.trim()) headers["Content-Type"] = "application/json";

      const res = await fetch(selected.path, {
        method: selected.method,
        headers,
        body: selected.method === "GET" ? undefined : body.trim() || undefined,
      });
      const text = await res.text();
      let pretty = text;
      try {
        pretty = JSON.stringify(JSON.parse(text), null, 2);
      } catch {
        /* SSE and empty bodies are not JSON; show them as they came */
      }
      setResponse({ status: res.status, text: pretty, ms: Math.round(performance.now() - started) });
    } catch (err) {
      setResponse({
        status: 0,
        text: err instanceof Error ? err.message : String(err),
        ms: Math.round(performance.now() - started),
      });
    } finally {
      setBusy(false);
    }
  }

  const curl = buildCurl(selected, body);

  return (
    <div className="stack">
      <section className="panel">
        <h2>API</h2>
        <p className="muted">
          Every call below is sent with your current token, so the response is what your own roles
          and tenant return — not what the schema says is possible. The full OpenAPI description
          is at{" "}
          <a href="/swagger-ui.html" target="_blank" rel="noreferrer">
            /swagger-ui.html
          </a>
          .
        </p>

        <div className="api">
          <nav className="api-list">
            {ENDPOINTS.map((group) => (
              <div key={group.group}>
                <h3>{group.group}</h3>
                {group.items.map((e) => (
                  <button
                    key={`${e.method} ${e.path}`}
                    className={`api-item${selected.path === e.path && selected.method === e.method ? " picked" : ""}`}
                    onClick={() => choose(e)}
                  >
                    <span className={`verb ${e.method.toLowerCase()}`}>{e.method}</span>
                    <code>{e.path}</code>
                  </button>
                ))}
              </div>
            ))}
          </nav>

          <div className="api-detail">
            <div className="meta">
              <span className={`verb ${selected.method.toLowerCase()}`}>{selected.method}</span>
              <code>{selected.path}</code>
              {selected.admin && <span className="chip warn">admin role</span>}
            </div>
            <p className="muted">{selected.summary}</p>

            {selected.method !== "GET" && (
              <>
                <h3>Request body</h3>
                <textarea rows={10} value={body} onChange={(e) => setBody(e.target.value)} />
              </>
            )}

            <div className="row">
              <button className="primary" disabled={busy} onClick={() => void send()}>
                {busy ? "…" : "Send"}
              </button>
              <button onClick={() => void navigator.clipboard?.writeText(curl)}>Copy as curl</button>
            </div>

            <h3>curl</h3>
            <pre className="code-block">{curl}</pre>

            {response && (
              <>
                <h3>Response</h3>
                <div className="meta">
                  <span className={`chip ${response.status >= 200 && response.status < 300 ? "ok" : "bad"}`}>
                    HTTP {response.status || "network error"}
                  </span>
                  <span className="chip">{response.ms} ms</span>
                </div>
                <pre className="code-block">{response.text || "(empty body)"}</pre>
              </>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}

/** A curl the reader can paste elsewhere. The token is deliberately left as a placeholder. */
function buildCurl(endpoint: Endpoint, body: string) {
  const lines = [`curl -X ${endpoint.method} http://localhost:8081${endpoint.path} \\`];
  lines.push(`  -H "Authorization: Bearer $TOKEN" \\`);
  if (endpoint.method !== "GET" && body.trim()) {
    lines.push(`  -H "Content-Type: application/json" \\`);
    lines.push(`  -d '${body.replace(/\n\s*/g, "")}'`);
  } else {
    lines[lines.length - 1] = lines[lines.length - 1].replace(/ \\$/, "");
  }
  return lines.join("\n");
}
