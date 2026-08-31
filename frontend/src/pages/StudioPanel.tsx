import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type DocumentView, type Insight, type Transformation } from "../api/client";

/**
 * Transformations run against one source, and the outputs they produced.
 *
 * <p>Distinct from asking a question, and worth keeping visibly separate in the UI: a question
 * retrieves the few passages most like it, while a transformation reads the whole document.
 * "Summarise this" cannot be answered from the chunks nearest the word "summarise".
 *
 * The cached badge is not decoration. An identical run is served from an earlier one, and
 * presenting that as fresh would leave someone editing a prompt unable to tell whether the
 * output in front of them reflects the edit.
 */
export default function StudioPanel({ document }: { document: DocumentView }) {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [transformations, setTransformations] = useState<Transformation[]>([]);
  const [insights, setInsights] = useState<Insight[]>([]);
  const [running, setRunning] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [ts, is] = await Promise.all([api.transformations(token), api.insights(token, document.id)]);
      setTransformations(ts);
      setInsights(is);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token, document.id]);

  useEffect(() => {
    void load();
  }, [load]);

  async function run(slug: string) {
    setRunning(slug);
    setError(null);
    try {
      const insight = await api.runTransformation(token, document.id, slug);
      setInsights((prev) => [insight, ...prev]);
      setOpen(insight.id);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setRunning(null);
    }
  }

  async function remove(id: string) {
    await api.deleteInsight(token, id);
    setInsights((prev) => prev.filter((i) => i.id !== id));
  }

  const ready = document.status === "READY";

  return (
    <div className="studio">
      <div className="rail-head">
        <h3>Studio</h3>
        <span className="muted small">{document.filename}</span>
      </div>

      {!ready && <p className="muted small">Available once this source has finished ingesting.</p>}

      {ready && (
        <div className="transform-buttons">
          {transformations.map((t) => (
            <button key={t.slug} disabled={running !== null} title={t.description ?? t.name} onClick={() => void run(t.slug)}>
              {running === t.slug ? "running…" : t.name}
            </button>
          ))}
        </div>
      )}
      {error && <p className="error small">{error}</p>}

      {insights.length === 0 && ready && (
        <p className="muted small">
          Nothing yet. A transformation reads the whole source, rather than retrieving passages
          from it the way a question does.
        </p>
      )}

      <ul className="insights">
        {insights.map((i) => (
          <li key={i.id}>
            <button className="insight-head" onClick={() => setOpen(open === i.id ? null : i.id)}>
              <strong>{i.transformationName}</strong>
              <span className="spacer" />
              {i.fromCache ? (
                <span className="chip ok" title="Reused from an identical earlier run">
                  cached
                </span>
              ) : (
                <span className="chip" title="Computed by the model">
                  fresh
                </span>
              )}
              {i.durationMs != null && <span className="chip">{formatMs(i.durationMs)}</span>}
              {i.passes > 1 && (
                <span className="chip warn" title="The source was too long to read in one call">
                  {i.passes} passes
                </span>
              )}
            </button>
            {open === i.id && (
              <div className="insight-body">
                <p className="passage">{i.output}</p>
                <div className="meta">
                  <span className="muted small">{i.model ?? "unknown model"}</span>
                  <span className="spacer" />
                  <button className="link" onClick={() => void navigator.clipboard?.writeText(i.output)}>
                    copy
                  </button>
                  <button className="link" onClick={() => void remove(i.id)}>
                    delete
                  </button>
                </div>
              </div>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Sub-second results are the point of the cache, so don't round them away. */
function formatMs(ms: number) {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}
