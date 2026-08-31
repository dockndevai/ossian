import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, askStream, type Citation, type DocumentView } from "../api/client";

/**
 * The notebook: sources on the left, conversation in the middle, the cited passage on the right.
 *
 * The selection in the sources rail is not decoration — it is passed to the backend as
 * documentIds, so retrieval is genuinely narrowed rather than the answer being filtered after
 * the fact. Selecting nothing searches everything, which is the behaviour people expect from an
 * empty filter.
 */

interface Turn {
  id: number;
  question: string;
  answer: string;
  citations: Citation[];
  grounded: boolean | null;
  latencyMs: number | null;
  streaming: boolean;
  error?: string;
}

const POLL_MS = 2500;

export default function NotebookPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [turns, setTurns] = useState<Turn[]>([]);
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [openCitation, setOpenCitation] = useState<Citation | null>(null);
  const [dragging, setDragging] = useState(false);
  const threadRef = useRef<HTMLDivElement>(null);
  const nextId = useRef(1);

  const loadDocs = useCallback(async () => {
    try {
      const page = await api.documents(token, 0);
      setDocs(page.content);
      return page.content;
    } catch {
      return [];
    }
  }, [token]);

  useEffect(() => {
    void loadDocs();
  }, [loadDocs]);

  // Ingestion is asynchronous, so a freshly uploaded source sits at PENDING for a few seconds.
  // Poll only while something is actually in flight rather than on a permanent timer.
  useEffect(() => {
    const pending = docs.some((d) => d.status === "PENDING" || d.status === "PROCESSING");
    if (!pending) return;
    const timer = setInterval(() => void loadDocs(), POLL_MS);
    return () => clearInterval(timer);
  }, [docs, loadDocs]);

  useEffect(() => {
    threadRef.current?.scrollTo({ top: threadRef.current.scrollHeight, behavior: "smooth" });
  }, [turns]);

  function toggle(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function upload(files: FileList | File[]) {
    setUploadError(null);
    for (const file of Array.from(files)) {
      try {
        await api.upload(token, file);
      } catch (err) {
        setUploadError(`${file.name}: ${err instanceof Error ? err.message : String(err)}`);
      }
    }
    await loadDocs();
  }

  async function remove(id: string) {
    await api.deleteDocument(token, id);
    setSelected((prev) => {
      const next = new Set(prev);
      next.delete(id);
      return next;
    });
    await loadDocs();
  }

  async function ask(e: React.FormEvent) {
    e.preventDefault();
    const q = question.trim();
    if (!q || busy) return;

    const documentIds = selected.size ? Array.from(selected) : undefined;
    const id = nextId.current++;
    setTurns((t) => [...t, { id, question: q, answer: "", citations: [], grounded: null, latencyMs: null, streaming: true }]);
    setQuestion("");
    setBusy(true);

    const started = performance.now();
    try {
      // Stream the prose so the answer appears as it is written, then fetch the same question
      // non-streaming for its citations — the SSE endpoint carries tokens only.
      await askStream(token, { question: q, documentIds }, (chunk) => {
        setTurns((t) => t.map((turn) => (turn.id === id ? { ...turn, answer: turn.answer + chunk } : turn)));
      });
      const full = await api.ask(token, q, documentIds);
      setTurns((t) =>
        t.map((turn) =>
          turn.id === id
            ? {
                ...turn,
                answer: full.answer,
                citations: full.citations,
                grounded: full.answeredFromContext,
                latencyMs: Math.round(performance.now() - started),
                streaming: false,
              }
            : turn,
        ),
      );
    } catch (err) {
      setTurns((t) =>
        t.map((turn) =>
          turn.id === id
            ? { ...turn, streaming: false, error: err instanceof Error ? err.message : String(err) }
            : turn,
        ),
      );
    } finally {
      setBusy(false);
    }
  }

  const ready = docs.filter((d) => d.status === "READY").length;

  return (
    <div className="notebook">
      <aside
        className={`sources${dragging ? " dragging" : ""}`}
        onDragOver={(e) => {
          e.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragging(false);
          if (e.dataTransfer.files.length) void upload(e.dataTransfer.files);
        }}
      >
        <div className="rail-head">
          <h3>Sources</h3>
          <span className="muted small">
            {selected.size ? `${selected.size} selected` : `all ${ready}`}
          </span>
        </div>

        <label className="dropzone">
          <input
            type="file"
            multiple
            hidden
            onChange={(e) => e.target.files && void upload(e.target.files)}
          />
          <strong>Add a source</strong>
          <span className="muted small">drop files here, or click to choose</span>
        </label>
        {uploadError && <p className="error small">{uploadError}</p>}

        {docs.length === 0 && <p className="muted small">Nothing yet. Add a source to ask about it.</p>}

        <ul className="source-list">
          {docs.map((d) => (
            <li key={d.id} className={selected.has(d.id) ? "picked" : undefined}>
              <label>
                <input
                  type="checkbox"
                  checked={selected.has(d.id)}
                  disabled={d.status !== "READY"}
                  onChange={() => toggle(d.id)}
                />
                <span className="source-name" title={d.filename}>
                  {d.filename}
                </span>
              </label>
              <div className="source-meta">
                <span className={`chip ${statusClass(d.status)}`}>{d.status.toLowerCase()}</span>
                {d.status === "READY" && <span className="chip">{d.chunkCount} chunks</span>}
                <span className="spacer" />
                <button className="link" title="Remove source" onClick={() => void remove(d.id)}>
                  remove
                </button>
              </div>
              {d.errorMessage && <p className="error small">{d.errorMessage}</p>}
            </li>
          ))}
        </ul>

        {selected.size > 0 && (
          <button className="link" onClick={() => setSelected(new Set())}>
            clear selection — search everything
          </button>
        )}
      </aside>

      <section className="thread-pane">
        <div className="thread" ref={threadRef}>
          {turns.length === 0 && (
            <div className="empty">
              <h2>Ask your sources</h2>
              <p className="muted">
                Answers are grounded in the documents on the left, and every claim carries a
                citation you can open. Nothing in the corpus means no answer, on purpose.
              </p>
              <div className="suggestions">
                {[
                  "Can we deploy on a Friday?",
                  "How much notice do I need before taking leave?",
                  "What is the refund window for enterprise customers?",
                ].map((s) => (
                  <button key={s} onClick={() => setQuestion(s)}>
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}

          {turns.map((turn) => (
            <article key={turn.id} className="turn">
              <p className="q">{turn.question}</p>

              {turn.error ? (
                <p className="error">{turn.error}</p>
              ) : (
                <div className="a">
                  <AnswerText
                    text={turn.answer}
                    citations={turn.citations}
                    onOpen={setOpenCitation}
                  />
                  {turn.streaming && <span className="caret" />}
                </div>
              )}

              {!turn.streaming && !turn.error && (
                <div className="meta">
                  <span className={turn.grounded ? "chip ok" : "chip warn"}>
                    {turn.grounded ? "grounded" : "not in your sources"}
                  </span>
                  {turn.latencyMs != null && <span className="chip">{turn.latencyMs} ms</span>}
                  {turn.citations.map((c) => (
                    <button key={c.index} className="cite-chip" onClick={() => setOpenCitation(c)}>
                      [{c.index}] {c.filename}
                      {c.score != null && <em> {c.score.toFixed(2)}</em>}
                    </button>
                  ))}
                </div>
              )}
            </article>
          ))}
        </div>

        <form className="composer" onSubmit={ask}>
          <textarea
            value={question}
            rows={2}
            placeholder={
              selected.size
                ? `Ask about the ${selected.size} selected source${selected.size > 1 ? "s" : ""}…`
                : "Ask anything about your sources…"
            }
            onChange={(e) => setQuestion(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void ask(e as unknown as React.FormEvent);
              }
            }}
          />
          <button className="primary" disabled={busy || !question.trim()}>
            {busy ? "…" : "Ask"}
          </button>
        </form>
      </section>

      {openCitation && (
        <aside className="citation-pane">
          <div className="rail-head">
            <h3>Source</h3>
            <button className="link" onClick={() => setOpenCitation(null)}>
              close
            </button>
          </div>
          <strong>{openCitation.filename}</strong>
          <div className="meta">
            <span className="chip">citation {openCitation.index}</span>
            {openCitation.score != null && (
              <span className="chip">similarity {openCitation.score.toFixed(3)}</span>
            )}
          </div>
          <p className="passage">{openCitation.excerpt}</p>
          <p className="muted small">
            This is the passage the retriever handed the model — the exact text the answer was
            written from.
          </p>
        </aside>
      )}
    </div>
  );
}

/** Renders [1] markers in the answer as buttons that open the matching citation. */
function AnswerText({
  text,
  citations,
  onOpen,
}: {
  text: string;
  citations: Citation[];
  onOpen: (c: Citation) => void;
}) {
  if (citations.length === 0) return <>{text}</>;

  const parts = text.split(/(\[\d+\])/g);
  return (
    <>
      {parts.map((part, i) => {
        const match = /^\[(\d+)\]$/.exec(part);
        const cite = match ? citations.find((c) => c.index === Number(match[1])) : undefined;
        if (!cite) return <span key={i}>{part}</span>;
        return (
          <button key={i} className="inline-cite" onClick={() => onOpen(cite)} title={cite.filename}>
            {part}
          </button>
        );
      })}
    </>
  );
}

function statusClass(status: DocumentView["status"]) {
  if (status === "READY") return "ok";
  if (status === "FAILED") return "bad";
  return "warn";
}
