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

export const api = {
  ask: (token: string | undefined, question: string, documentIds?: string[]) =>
    request<AskResponse>(token, "/chat", {
      method: "POST",
      body: JSON.stringify({ question, documentIds }),
    }),

  documents: (token: string | undefined, page = 0) =>
    request<Page<DocumentView>>(token, `/documents?page=${page}&size=20`),

  upload: (token: string | undefined, file: File) => {
    const form = new FormData();
    form.append("file", file);
    return request<{ documentId: string; jobId: string | null; status: string; duplicate: boolean }>(
      token,
      "/documents",
      { method: "POST", body: form },
    );
  },

  deleteDocument: (token: string | undefined, id: string) =>
    request<void>(token, `/documents/${id}`, { method: "DELETE" }),

  corpusStats: (token: string | undefined) =>
    request<{ documents: number; ready: number; failed: number; chunks: number; bytes: number }>(
      token,
      "/admin/stats/corpus",
    ),

  retrievalStats: (token: string | undefined) =>
    request<{
      questionsLast7d: number;
      unansweredLast7d: number;
      answerRate: number | null;
      avgLatencyMs: number | null;
      avgTopScore: number | null;
    }>(token, "/admin/stats/retrieval"),

  gaps: (token: string | undefined) =>
    request<{ question: string; chunksRetrieved: number; topScore: number | null; createdAt: string }[]>(
      token,
      "/admin/gaps",
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
};
