import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api } from "../api/client";

type Corpus = Awaited<ReturnType<typeof api.corpusStats>>;
type Retrieval = Awaited<ReturnType<typeof api.retrievalStats>>;
type Gap = Awaited<ReturnType<typeof api.gaps>>[number];
type Job = Awaited<ReturnType<typeof api.jobs>>["content"][number];

export default function AdminPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  const [corpus, setCorpus] = useState<Corpus | null>(null);
  const [retrieval, setRetrieval] = useState<Retrieval | null>(null);
  const [gaps, setGaps] = useState<Gap[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [c, r, g, j] = await Promise.all([
        api.corpusStats(token),
        api.retrievalStats(token),
        api.gaps(token),
        api.jobs(token),
      ]);
      setCorpus(c);
      setRetrieval(r);
      setGaps(g);
      setJobs(j.content);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  const pct = (v: number | null | undefined) => (v == null ? "—" : `${(v * 100).toFixed(0)}%`);

  return (
    <section className="panel">
      <h2>Retrieval maintenance</h2>
      {error && <p className="error">{error}</p>}

      <div className="cards">
        <div className="card"><span className="label">Documents</span><span className="value">{corpus?.documents ?? "—"}</span><span className="muted small">{corpus?.failed ?? 0} failed</span></div>
        <div className="card"><span className="label">Chunks</span><span className="value">{corpus?.chunks ?? "—"}</span><span className="muted small">{((corpus?.bytes ?? 0) / 1048576).toFixed(1)} MB source</span></div>
        <div className="card"><span className="label">Answer rate (7d)</span><span className="value">{pct(retrieval?.answerRate)}</span><span className="muted small">{retrieval?.unansweredLast7d ?? 0} unanswered of {retrieval?.questionsLast7d ?? 0}</span></div>
        <div className="card"><span className="label">Avg top score</span><span className="value">{retrieval?.avgTopScore?.toFixed(3) ?? "—"}</span><span className="muted small">{retrieval?.avgLatencyMs?.toFixed(0) ?? "—"} ms avg</span></div>
      </div>

      <h3>Coverage gaps</h3>
      <p className="muted">
        Questions the corpus could not answer. This is the most direct signal of what to ingest next.
      </p>
      <table>
        <thead><tr><th>Question</th><th>Chunks</th><th>Best score</th><th>When</th></tr></thead>
        <tbody>
          {gaps.map((g, i) => (
            <tr key={i}>
              <td>{g.question}</td>
              <td>{g.chunksRetrieved}</td>
              <td>{g.topScore?.toFixed(3) ?? "—"}</td>
              <td className="muted">{new Date(g.createdAt).toLocaleString()}</td>
            </tr>
          ))}
          {gaps.length === 0 && <tr><td colSpan={4} className="muted">No unanswered questions recorded.</td></tr>}
        </tbody>
      </table>

      <h3>Ingestion jobs</h3>
      <table>
        <thead><tr><th>Type</th><th>Status</th><th>Chunks</th><th>Duration</th><th>When</th></tr></thead>
        <tbody>
          {jobs.map((j) => (
            <tr key={j.id}>
              <td>{j.type}</td>
              <td>
                <span className={`chip ${j.status === "SUCCEEDED" ? "ok" : j.status === "FAILED" ? "bad" : "warn"}`}>{j.status}</span>
                {j.errorMessage && <div className="error small">{j.errorMessage}</div>}
              </td>
              <td>{j.chunksWritten}</td>
              <td>{j.durationMs != null ? `${j.durationMs} ms` : "—"}</td>
              <td className="muted">{new Date(j.createdAt).toLocaleString()}</td>
            </tr>
          ))}
          {jobs.length === 0 && <tr><td colSpan={5} className="muted">No jobs yet.</td></tr>}
        </tbody>
      </table>
    </section>
  );
}
