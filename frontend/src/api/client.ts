const BASE = import.meta.env.VITE_API_BASE ?? "/api";

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

/**
 * Thin fetch wrapper that attaches the bearer token.
 *
 * The token is passed in by the caller rather than read from storage here, so this module has
 * no opinion about how auth is stored and stays trivially testable.
 */
async function request<T>(token: string | undefined, path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const res = await fetch(`${BASE}${path}`, { ...init, headers });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      detail = body.message ?? body.error ?? detail;
    } catch {
      /* non-JSON error body; the status text will do */
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export interface DocumentView {
  id: string;
  filename: string;
  title: string | null;
  contentType: string | null;
  sizeBytes: number;
  status: "PENDING" | "PROCESSING" | "READY" | "FAILED";
  chunkCount: number;
  errorMessage: string | null;
  uploadedBy: string | null;
  createdAt: string;
}

export interface Citation {
  index: number;
  documentId: string;
  filename: string;
  score: number | null;
  excerpt: string;
}

export interface AskResponse {
  answer: string;
  citations: Citation[];
  answeredFromContext: boolean;
  latencyMs: number;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  number: number;
  size: number;
}

export interface ChunkView {
  id: string;
  documentId: string | null;
  filename: string | null;
  chunkIndex: number | null;
  characters: number;
  dimensions: number;
  norm: number;
  head: number[];
  excerpt: string;
}

export interface NeighbourView {
  id: string;
  documentId: string | null;
  filename: string | null;
  chunkIndex: number | null;
  similarity: number;
  excerpt: string;
}

export interface SearchResult {
  query: string;
  dimensions: number;
  queryNorm: number;
  neighbours: NeighbourView[];
  latencyMs: number;
}

export interface PointView {
  id: string;
  filename: string | null;
  x: number;
  y: number;
  excerpt: string;
}

export interface ProjectionResult {
  points: PointView[];
  explainedVariance: number;
  dimensions: number;
}

/**
 * Streams an answer token by token.
 *
 * EventSource cannot send an Authorization header and cannot POST, so the SSE frames are read
 * off a plain fetch instead. Data lines within one event rejoin with a newline, per the SSE
 * spec — without that, any answer containing a blank line arrives mangled. Each payload is a
 * JSON string, because the same spec strips a leading space from a data line and tokens
 * routinely start with one.
 */
export async function askStream(
  token: string | undefined,
  body: { question: string; documentIds?: string[]; namespace?: string },
  onToken: (chunk: string) => void,
  signal?: AbortSignal,
): Promise<void> {
  const headers = new Headers({ "Content-Type": "application/json", Accept: "text/event-stream" });
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const res = await fetch(`${BASE}/chat/stream`, { method: "POST", headers, body: JSON.stringify(body), signal });
  if (!res.ok || !res.body) throw new ApiError(res.status, res.statusText);

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let split: number;
    while ((split = buffer.indexOf("\n\n")) !== -1) {
      const frame = buffer.slice(0, split);
      buffer = buffer.slice(split + 2);
      const data = frame
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => line.slice(5).replace(/^ /, ""))
        .join("\n");
      if (!data) continue;
      // Tokens arrive JSON-quoted so their leading whitespace survives the SSE space rule.
      try {
        onToken(JSON.parse(data) as string);
      } catch {
        onToken(data);
      }
    }
  }
}

export interface NamespaceView {
  name: string;
  description: string | null;
  createdAt: string;
}

export interface SettingView {
  key: string;
  group: string;
  label: string;
  help: string;
  type: "INT" | "DOUBLE" | "STRING" | "TEXT";
  min: number | null;
  max: number | null;
  requiresReindex: boolean;
  defaultValue: string;
  override: string | null;
  effective: string;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface EventView {
  eventId: string;
  operation: string;
  externalId: string;
  namespace: string;
  source: string | null;
  documentId: string | null;
  status: string;
  errorMessage: string | null;
  createdAt: string;
}

export interface IngestEventRequest {
  eventId: string;
  operation: "UPSERT" | "DELETE";
  externalId: string;
  namespace?: string;
  source?: string;
  filename?: string;
  contentType?: string;
  text?: string;
  contentBase64?: string;
}

export interface EventResult {
  eventId: string;
  status: string;
  documentId: string | null;
  message: string | null;
}

/** Optional ?namespace= — absent means every namespace the tenant has, never more. */
function ns(namespace?: string) {
  return namespace ? `?namespace=${encodeURIComponent(namespace)}` : "";
}

export const api = {
  ask: (token: string | undefined, question: string, documentIds?: string[], namespace?: string) =>
    request<AskResponse>(token, "/chat", {
      method: "POST",
      body: JSON.stringify({ question, documentIds, namespace }),
    }),

  documents: (token: string | undefined, page = 0, namespace?: string) =>
    request<Page<DocumentView>>(
      token,
      `/documents?page=${page}&size=50${namespace ? `&namespace=${encodeURIComponent(namespace)}` : ""}`,
    ),

  upload: (token: string | undefined, file: File, namespace?: string) => {
    const form = new FormData();
    form.append("file", file);
    return request<{ documentId: string; jobId: string | null; status: string; duplicate: boolean }>(
      token,
      `/documents${namespace ? `?namespace=${encodeURIComponent(namespace)}` : ""}`,
      { method: "POST", body: form },
    );
  },

  namespaces: (token: string | undefined) =>
    request<NamespaceView[]>(token, "/namespaces"),

  createNamespace: (token: string | undefined, name: string, description?: string) =>
    request<NamespaceView>(token, "/namespaces", {
      method: "POST",
      body: JSON.stringify({ name, description }),
    }),

  settings: (token: string | undefined) => request<SettingView[]>(token, "/admin/settings"),

  updateSetting: (token: string | undefined, key: string, value: string) =>
    request<SettingView[]>(token, `/admin/settings/${encodeURIComponent(key)}`, {
      method: "PUT",
      body: JSON.stringify({ value }),
    }),

  resetSetting: (token: string | undefined, key: string) =>
    request<SettingView[]>(token, `/admin/settings/${encodeURIComponent(key)}`, { method: "DELETE" }),

  ingestEvents: (token: string | undefined, page = 0) =>
    request<Page<EventView>>(token, `/events/documents?page=${page}&size=50`),

  sendEvent: (token: string | undefined, event: IngestEventRequest) =>
    request<EventResult>(token, "/events/documents", {
      method: "POST",
      body: JSON.stringify(event),
    }),

  deleteDocument: (token: string | undefined, id: string) =>
    request<void>(token, `/documents/${id}`, { method: "DELETE" }),

  corpusStats: (token: string | undefined, namespace?: string) =>
    request<{ documents: number; ready: number; failed: number; chunks: number; bytes: number }>(
      token,
      `/admin/stats/corpus${ns(namespace)}`,
    ),

  retrievalStats: (token: string | undefined, namespace?: string) =>
    request<{
      questionsLast7d: number;
      unansweredLast7d: number;
      answerRate: number | null;
      avgLatencyMs: number | null;
      avgTopScore: number | null;
    }>(token, `/admin/stats/retrieval${ns(namespace)}`),

  gaps: (token: string | undefined, namespace?: string) =>
    request<{ question: string; chunksRetrieved: number; topScore: number | null; createdAt: string }[]>(
      token,
      `/admin/gaps${ns(namespace)}`,
    ),

  jobs: (token: string | undefined) =>
    request<Page<{
      id: string;
      documentId: string | null;
      type: string;
      status: string;
      chunksWritten: number;
      durationMs: number | null;
      errorMessage: string | null;
      createdAt: string;
    }>>(token, "/admin/jobs?page=0&size=20"),

  reindex: (token: string | undefined, id: string) =>
    request<unknown>(token, `/admin/documents/${id}/reindex`, { method: "POST" }),

  chunks: (token: string | undefined, documentId?: string, namespace?: string, page = 0, size = 50) =>
    request<Page<ChunkView>>(
      token,
      `/admin/vectors/chunks?page=${page}&size=${size}` +
        (documentId ? `&documentId=${encodeURIComponent(documentId)}` : "") +
        (namespace ? `&namespace=${encodeURIComponent(namespace)}` : ""),
    ),

  vectorSearch: (token: string | undefined, query: string, topK = 10, namespace?: string) =>
    request<SearchResult>(token, "/admin/vectors/search", {
      method: "POST",
      body: JSON.stringify({ query, topK, namespace }),
    }),

  projection: (token: string | undefined, namespace?: string) =>
    request<ProjectionResult>(token, `/admin/vectors/projection${ns(namespace)}`),
};
