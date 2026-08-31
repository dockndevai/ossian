import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, askStream, type Citation, type DocumentView } from "../api/client";
import StudioPanel from "./StudioPanel";
import { useNamespace } from "../app/NamespaceContext";

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
  const { current: namespace } = useNamespace();

  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [turns, setTurns] = useState<Turn[]>([]);
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [openCitation, setOpenCitation] = useState<Citation | null>(null);
  const [dragging, setDragging] = useState(false);
  const [urlValue, setUrlValue] = useState("");
  const [addingUrl, setAddingUrl] = useState(false);
  const [studioFor, setStudioFor] = useState<string | null>(null);
  const threadRef = useRef<HTMLDivElement>(null);
  const nextId = useRef(1);

  const loadDocs = useCallback(async () => {
    try {
      const page = await api.documents(token, 0, namespace ?? undefined);
      setDocs(page.content);
      return page.content;
    } catch {
      return [];
    }
  }, [token, namespace]);

  useEffect(() => {
    void loadDocs();
    // Switching namespace changes which sources exist, so a selection made in the old one is
    // meaningless here.
    setSelected(new Set());
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
        await api.upload(token, file, namespace ?? undefined);
      } catch (err) {
        setUploadError(`${file.name}: ${err instanceof Error ? err.message : String(err)}`);
      }
    }
    await loadDocs();
  }

  async function addUrl(e: React.FormEvent) {
    e.preventDefault();
    const url = urlValue.trim();
    if (!url || addingUrl) return;
    setAddingUrl(true);
    setUploadError(null);
    try {
      await api.addUrl(token, url, undefined, undefined);
      setUrlValue("");
      await loadDocs();
    } catch (err) {
      // The server refuses addresses only reachable from inside its own network, and says which
      // category — surface that rather than a generic failure.
      setUploadError(err instanceof Error ? err.message : String(err));
    } finally {
      setAddingUrl(false);
    }
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
      await askStream(token, { question: q, documentIds, namespace: namespace ?? undefined }, (chunk) => {
        setTurns((t) => t.map((turn) => (turn.id === id ? { ...turn, answer: turn.answer + chunk } : turn)));
      });
      const full = await api.ask(token, q, documentIds, namespace ?? undefined);
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
        <form className="url-add" onSubmit={addUrl}>
          <input
            type="url"
            value={urlValue}
            placeholder="or paste a link…"
            onChange={(e) => setUrlValue(e.target.value)}
          />
          <button disabled={addingUrl || !urlValue.trim()}>{addingUrl ? "…" : "Add"}</button>
        </form>
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
              {/* Facts on one line, actions on the next. Together they wrapped mid-chip and
                  clipped the last control, which is the state this rail is easiest to get into
                  because a filename can be any length. */}
              <div className="source-meta">
                <span className={`chip ${statusClass(d.status)}`}>{d.status.toLowerCase()}</span>
                {/* Only when looking at everything. Filtered to one namespace the label is on
                    every row and says nothing; unfiltered, two files of the same name in
                    different namespaces are otherwise indistinguishable. */}
                {!namespace && d.namespace && (
                  <span className="chip ns-chip" title={`In the ${d.namespace} namespace`}>
                    {d.namespace}
                  </span>
                )}
                {d.status === "READY" && (
                  <span className="chip">
                    {d.chunkCount} {d.chunkCount === 1 ? "chunk" : "chunks"}
                  </span>
                )}
                {d.sourceUrl && (
                  <a className="chip" href={d.sourceUrl} target="_blank" rel="noreferrer" title={d.sourceUrl}>
                    link
                  </a>
                )}
              </div>
              <div className="source-actions">
                <button
                  className="link"
                  title="Run transformations over this whole source"
                  onClick={() => setStudioFor(studioFor === d.id ? null : d.id)}
                >
                  {studioFor === d.id ? "hide studio" : "studio"}
                </button>
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

      {studioFor && !openCitation && (
        <aside className="citation-pane">
          <StudioPanel document={docs.find((d) => d.id === studioFor)!} />
        </aside>
      )}

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
