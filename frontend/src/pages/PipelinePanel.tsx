import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type FailureGroup, type StuckDocument, type Throughput } from "../api/client";

/**
 * Whether ingestion is working, and what to do when it is not.
 *
 * <p>A failure rate says something is wrong; the grouped reasons below say what, which is almost
 * always one bad file type or one oversized document rather than a general problem. Retry is a
 * button rather than a timer: most ingestion failures are deterministic, and retrying those on a
 * schedule spends the embedding budget rediscovering the same fact.
 */
export default function PipelinePanel() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [throughput, setThroughput] = useState<Throughput | null>(null);
  const [failures, setFailures] = useState<FailureGroup[]>([]);
  const [stuck, setStuck] = useState<StuckDocument[]>([]);
  const [hours, setHours] = useState(24);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [t, f, s] = await Promise.all([
        api.throughput(token, hours),
        api.failures(token, Math.max(hours, 168)),
        api.stuck(token),
      ]);
      setThroughput(t);
      setFailures(f);
      setStuck(s);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token, hours]);

  useEffect(() => {
    void load();
  }, [load]);

  async function retry(id: string) {
    setRetrying(id);
    setError(null);
    try {
      await api.retryIngest(token, id);
      // Ingestion is asynchronous, so the row will not have changed yet. Give it a moment
      // rather than showing the same failure back and looking like nothing happened.
      setTimeout(() => void load(), 2500);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setRetrying(null);
    }
  }

  return (
    <section className="panel">
      <div className="rail-head">
        <h2>Ingestion</h2>
        <select value={hours} onChange={(e) => setHours(Number(e.target.value))}>
          <option value={1}>last hour</option>
          <option value={24}>last 24 hours</option>
          <option value={168}>last 7 days</option>
          <option value={720}>last 30 days</option>
        </select>
      </div>

      {throughput && (
        <div className="cards">
          <Card label="Documents" value={throughput.documents} />
          <Card
            label="Succeeded"
            value={
              throughput.successRate == null
                ? "—"
                : `${Math.round(throughput.successRate * 100)}%`
            }
            tone={throughput.failed > 0 ? "warn" : "ok"}
          />
          <Card label="Failed" value={throughput.failed} tone={throughput.failed > 0 ? "bad" : undefined} />
          <Card label="Chunks written" value={throughput.chunks} />
          <Card
            label="Average"
            value={throughput.avgDurationMs == null ? "—" : formatMs(throughput.avgDurationMs)}
          />
          {/* The tail is what people actually wait on; an average hides one document taking a
              minute behind fifty taking a second. */}
          <Card
            label="p95"
            value={throughput.p95DurationMs == null ? "—" : formatMs(throughput.p95DurationMs)}
          />
        </div>
      )}

      {error && <p className="error">{error}</p>}

      <h3>Needs attention</h3>
      {stuck.length === 0 ? (
        <p className="muted small">
          Nothing stuck. This lists documents that failed, and anything left processing for over an
          hour — nothing marks those failed, because the process that would have is the one that
          died.
        </p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Document</th>
              <th>Namespace</th>
              <th>State</th>
              <th>Reason</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {stuck.map((d) => (
              <tr key={d.documentId}>
                <td>{d.filename}</td>
                <td className="muted">{d.namespace}</td>
                <td>
                  <span className={`chip ${d.status === "FAILED" ? "bad" : "warn"}`}>
                    {d.status.toLowerCase()}
                  </span>
                </td>
                <td className="muted small">{d.errorMessage ?? "no reason recorded"}</td>
                <td>
                  <button
                    className="link"
                    disabled={retrying === d.documentId}
                    onClick={() => void retry(d.documentId)}
                  >
                    {retrying === d.documentId ? "…" : "retry"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {failures.length > 0 && (
        <>
          <h3>Failures by cause</h3>
          <p className="muted small">
            Grouped on the first line of the message. Stack traces and ids make every failure look
            unique, and twenty failures that are really one cause is the view that wastes the most
            time.
          </p>
          <table>
            <thead>
              <tr>
                <th>Cause</th>
                <th>Count</th>
                <th>Most recent</th>
              </tr>
            </thead>
            <tbody>
              {failures.map((f) => (
                <tr key={f.reason}>
                  <td>{f.reason}</td>
                  <td>{f.count}</td>
                  <td className="muted small">{new Date(f.mostRecent).toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}

function Card({ label, value, tone }: { label: string; value: string | number; tone?: string }) {
  return (
    <div className="card">
      <span className="label">{label}</span>
      <span className={`value${tone ? ` ${tone}` : ""}`}>{value}</span>
    </div>
  );
}

function formatMs(ms: number) {
  return ms < 1000 ? `${Math.round(ms)} ms` : `${(ms / 1000).toFixed(1)} s`;
}
