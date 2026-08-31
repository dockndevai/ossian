import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type DocumentView } from "../api/client";
import { useNamespace } from "../app/NamespaceContext";
import PipelinePanel from "./PipelinePanel";
import AuditPanel from "./AuditPanel";

/**
 * The maintenance view.
 *
 * The number that matters is the answer rate, and the list that matters is the gaps: questions
 * the corpus could not answer are a direct instruction about what to ingest next. Corpus size
 * on its own says nothing about whether the thing works.
 */

interface Corpus {
  documents: number;
  ready: number;
  failed: number;
  chunks: number;
  bytes: number;
}

interface Retrieval {
  questionsLast7d: number;
  unansweredLast7d: number;
  answerRate: number | null;
  avgLatencyMs: number | null;
  avgTopScore: number | null;
}

interface Gap {
  question: string;
  chunksRetrieved: number;
  topScore: number | null;
  createdAt: string;
}

interface Job {
  id: string;
  documentId: string | null;
  type: string;
  status: string;
  chunksWritten: number;
  durationMs: number | null;
  errorMessage: string | null;
  createdAt: string;
}

export default function AdminPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  const { current: namespace } = useNamespace();

  const [corpus, setCorpus] = useState<Corpus | null>(null);
  const [retrieval, setRetrieval] = useState<Retrieval | null>(null);
  const [gaps, setGaps] = useState<Gap[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [c, r, g, j, d] = await Promise.all([
        api.corpusStats(token, namespace ?? undefined),
        api.retrievalStats(token, namespace ?? undefined),
        api.gaps(token, namespace ?? undefined),
        api.jobs(token),
        api.documents(token, 0, namespace ?? undefined),
      ]);
      setCorpus(c);
      setRetrieval(r);
      setGaps(g);
      setJobs(j.content);
      setDocs(d.content);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token, namespace]);

  useEffect(() => {
    void load();
  }, [load]);

  async function reindex(id: string) {
    setBusy(id);
    try {
      await api.reindex(token, id);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="stack">
      <div className="scope-banner">
        {namespace
          ? `Showing ${namespace}. Ingestion jobs are listed across all namespaces.`
          : "Showing every namespace."}
      </div>

      <PipelinePanel />

      <AuditPanel />

      <section className="panel">
        <h2>Corpus</h2>
        {error && <p className="error">{error}</p>}
        <div className="cards">
          <Stat label="Documents" value={corpus?.documents} />
          <Stat label="Ready" value={corpus?.ready} />
          <Stat label="Failed" value={corpus?.failed} tone={corpus?.failed ? "bad" : undefined} />
          <Stat label="Chunks" value={corpus?.chunks} />
          <Stat label="Size" value={corpus ? formatBytes(corpus.bytes) : undefined} />
        </div>
      </section>

      <section className="panel">
        <h2>Retrieval, last 7 days</h2>
        <div className="cards">
          <Stat label="Questions" value={retrieval?.questionsLast7d} />
          <Stat
            label="Answer rate"
            value={retrieval?.answerRate != null ? `${(retrieval.answerRate * 100).toFixed(0)}%` : "—"}
            tone={retrieval?.answerRate != null && retrieval.answerRate < 0.7 ? "warn" : undefined}
          />
          <Stat label="Unanswered" value={retrieval?.unansweredLast7d} />
          <Stat
            label="Avg latency"
            value={retrieval?.avgLatencyMs != null ? `${Math.round(retrieval.avgLatencyMs)} ms` : "—"}
          />
          <Stat
            label="Avg top score"
            value={retrieval?.avgTopScore != null ? retrieval.avgTopScore.toFixed(3) : "—"}
          />
        </div>
        <p className="muted small">
          Answer rate is the honest health metric. A corpus that cannot answer what people
          actually ask is failing however large it is.
        </p>
      </section>

      <section className="panel">
        <h2>Gaps</h2>
        <p className="muted">
          Questions that retrieved nothing. This is the backlog of what to ingest next.
        </p>
        {gaps.length === 0 ? (
          <p className="muted">No unanswered questions. Either the corpus covers what is asked, or
            nobody has asked yet.</p>
        ) : (
          <table>
            <thead>
              <tr>
                <th>Question</th>
                <th>Chunks</th>
                <th>Asked</th>
              </tr>
            </thead>
            <tbody>
              {gaps.map((g, i) => (
                <tr key={i}>
                  <td>{g.question}</td>
                  <td className="num">{g.chunksRetrieved}</td>
                  <td className="muted small">{new Date(g.createdAt).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="panel">
        <h2>Documents</h2>
        <p className="muted">
          Reindex rebuilds a document's chunks from the stored original — what makes a chunking or
          embedding-model change safe to roll out.
        </p>
        <table>
          <thead>
            <tr>
              <th>File</th>
              <th>Status</th>
              <th>Chunks</th>
              <th>Size</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {docs.map((d) => (
              <tr key={d.id}>
                <td>{d.filename}</td>
                <td>
                  <span className={`chip ${d.status === "READY" ? "ok" : d.status === "FAILED" ? "bad" : "warn"}`}>
                    {d.status.toLowerCase()}
                  </span>
                </td>
                <td className="num">{d.chunkCount}</td>
                <td className="num">{formatBytes(d.sizeBytes)}</td>
                <td>
                  <button disabled={busy === d.id} onClick={() => void reindex(d.id)}>
                    {busy === d.id ? "…" : "Reindex"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="panel">
        <h2>Ingestion jobs</h2>
        <table>
          <thead>
            <tr>
              <th>Type</th>
              <th>Status</th>
              <th>Chunks</th>
              <th>Took</th>
              <th>When</th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((j) => (
              <tr key={j.id}>
                <td>{j.type.toLowerCase()}</td>
                <td>
                  <span className={`chip ${j.status === "SUCCEEDED" ? "ok" : j.status === "FAILED" ? "bad" : "warn"}`}>
                    {j.status.toLowerCase()}
                  </span>
                  {j.errorMessage && <p className="error small">{j.errorMessage}</p>}
                </td>
                <td className="num">{j.chunksWritten}</td>
                <td className="num">{j.durationMs != null ? `${j.durationMs} ms` : "—"}</td>
                <td className="muted small">{new Date(j.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {jobs.length === 0 && <p className="muted">No ingestion has run yet.</p>}
      </section>
    </div>
  );
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string;
  value: number | string | undefined;
  tone?: "bad" | "warn";
}) {
  return (
    <div className="card">
      <span className="label">{label}</span>
      <span className={`value${tone ? ` ${tone}` : ""}`}>{value ?? "—"}</span>
    </div>
  );
}

function formatBytes(n: number) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} kB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
