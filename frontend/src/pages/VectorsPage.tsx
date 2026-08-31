import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "react-oidc-context";
import {
  api,
  type ChunkView,
  type DocumentView,
  type ProjectionResult,
  type SearchResult,
} from "../api/client";
import { useNamespace } from "../app/NamespaceContext";

/**
 * What the retriever actually sees.
 *
 * A RAG system fails in two places that look identical from the outside: the retriever hands
 * over the wrong passage, or the model misreads the right one. This page separates them. Run a
 * query here and no model is involved — if the passage you expected is at the top, retrieval is
 * fine and the model is the problem.
 */
export default function VectorsPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  const { current: namespace } = useNamespace();

  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [filter, setFilter] = useState<string>("");
  const [chunks, setChunks] = useState<ChunkView[]>([]);
  const [total, setTotal] = useState(0);
  const [open, setOpen] = useState<string | null>(null);
  const [projection, setProjection] = useState<ProjectionResult | null>(null);
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<SearchResult | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hovered, setHovered] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      const [page, proj, docPage] = await Promise.all([
        api.chunks(token, filter || undefined, namespace ?? undefined, 0, 100),
        api.projection(token, namespace ?? undefined),
        api.documents(token, 0, namespace ?? undefined),
      ]);
      setChunks(page.content);
      setTotal(page.totalElements);
      setProjection(proj);
      setDocs(docPage.content);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token, filter, namespace]);

  useEffect(() => {
    // A document chosen in another namespace is not in this one; keeping it would empty the
    // table with no visible cause.
    setFilter("");
  }, [namespace]);

  useEffect(() => {
    void load();
  }, [load]);

  async function search(e: React.FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setSearching(true);
    setError(null);
    try {
      setResult(await api.vectorSearch(token, query.trim(), 10, namespace ?? undefined));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSearching(false);
    }
  }

  // One stable colour per filename, so a point on the map and a row in the table agree.
  const colours = useMemo(() => {
    const names = Array.from(new Set(projection?.points.map((p) => p.filename ?? "?") ?? [])).sort();
    const map = new Map<string, string>();
    names.forEach((n, i) => map.set(n, `hsl(${(i * 67) % 360} 65% 55%)`));
    return map;
  }, [projection]);

  return (
    <div className="stack">
      <section className="panel">
        <h2>Vector store</h2>
        <p className="muted">
          Every chunk that was embedded, exactly as stored. {total} chunk{total === 1 ? "" : "s"}
          {projection?.dimensions ? ` at ${projection.dimensions} dimensions` : ""}
          {namespace ? ` in ${namespace}` : " across all namespaces"}.
        </p>

        {error && <p className="error">{error}</p>}

        <div className="toolbar">
          <select value={filter} onChange={(e) => setFilter(e.target.value)}>
            <option value="">all documents</option>
            {docs.map((d) => (
              <option key={d.id} value={d.id}>
                {d.filename}
              </option>
            ))}
          </select>
          <button onClick={() => void load()}>Refresh</button>
        </div>

        <table>
          <thead>
            <tr>
              <th>Source</th>
              <th>Chunk</th>
              <th>Chars</th>
              <th>Dims</th>
              <th>Norm</th>
              <th>Embedding</th>
            </tr>
          </thead>
          <tbody>
            {chunks.map((c) => (
              <Fragment key={c.id}>
                <tr
                  className="clickable"
                  onClick={() => setOpen(open === c.id ? null : c.id)}
                >
                  <td>
                    <span className="dot" style={{ background: colours.get(c.filename ?? "?") }} />
                    {c.filename}
                  </td>
                  <td>{c.chunkIndex}</td>
                  <td className="num">{c.characters}</td>
                  <td className="num">{c.dimensions}</td>
                  {/* Normalised embeddings all have norm 1. A row that does not is a sign the
                      embedding model changed under the corpus. */}
                  <td className="num">{c.norm.toFixed(4)}</td>
                  <td>
                    <Sparkline values={c.head} />
                  </td>
                </tr>
                {open === c.id && (
                  <tr>
                    <td colSpan={6}>
                      <div className="detail">
                        <p className="passage">{c.excerpt}</p>
                        <p className="muted small">
                          first {c.head.length} of {c.dimensions} values:{" "}
                          <code>[{c.head.map((v) => v.toFixed(4)).join(", ")}, …]</code>
                        </p>
                      </div>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
        {chunks.length === 0 && <p className="muted">No chunks. Ingest a document first.</p>}
      </section>

      <section className="panel">
        <h2>Retrieval playground</h2>
        <p className="muted">
          Embeds your query and returns its nearest chunks — no model in the loop. This is how you
          tell a retrieval failure from a generation failure.
        </p>
        <form className="toolbar" onSubmit={search}>
          <input
            className="grow"
            value={query}
            placeholder="how much notice before taking leave"
            onChange={(e) => setQuery(e.target.value)}
          />
          <button className="primary" disabled={searching || !query.trim()}>
            {searching ? "…" : "Search"}
          </button>
        </form>

        {result && (
          <>
            <div className="meta">
              <span className="chip">{result.dimensions} dims</span>
              <span className="chip">query norm {result.queryNorm.toFixed(4)}</span>
              <span className="chip">{result.latencyMs} ms</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>Similarity</th>
                  <th>Source</th>
                  <th>Chunk</th>
                  <th>Passage</th>
                </tr>
              </thead>
              <tbody>
                {result.neighbours.map((n) => (
                  <tr key={n.id}>
                    <td className="num">
                      <div className="bar" style={{ ["--v" as string]: `${Math.max(0, n.similarity) * 100}%` }}>
                        <span>{n.similarity.toFixed(4)}</span>
                      </div>
                    </td>
                    <td>
                      <span className="dot" style={{ background: colours.get(n.filename ?? "?") }} />
                      {n.filename}
                    </td>
                    <td>{n.chunkIndex}</td>
                    <td className="passage small">{n.excerpt}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {result.neighbours.length === 0 && <p className="muted">Nothing stored to compare against.</p>}
          </>
        )}
      </section>

      <section className="panel">
        <h2>Embedding map</h2>
        <p className="muted">
          The corpus projected to two dimensions. Chunks of one document normally cluster; one
          that scatters usually covers unrelated topics and would retrieve better split up.
        </p>
        {projection && projection.points.length > 0 ? (
          <>
            <Scatter points={projection.points} colours={colours} hovered={hovered} onHover={setHovered} />
            <p className="muted small">
              These two axes carry {(projection.explainedVariance * 100).toFixed(1)}% of the
              variance in {projection.dimensions} dimensions. The rest is not on screen — points
              that look close here are not necessarily close to the retriever.
            </p>
            <div className="legend">
              {Array.from(colours.entries()).map(([name, colour]) => (
                <span key={name} className="chip">
                  <span className="dot" style={{ background: colour }} />
                  {name}
                </span>
              ))}
            </div>
          </>
        ) : (
          <p className="muted">Nothing to plot yet.</p>
        )}
      </section>
    </div>
  );
}

/** A tiny bar chart of an embedding's leading values — enough to see it is not all zeroes. */
function Sparkline({ values }: { values: number[] }) {
  const max = Math.max(0.0001, ...values.map(Math.abs));
  return (
    <span className="spark" title={values.map((v) => v.toFixed(4)).join(", ")}>
      {values.map((v, i) => (
        <span
          key={i}
          className={v < 0 ? "neg" : "pos"}
          style={{ height: `${(Math.abs(v) / max) * 100}%` }}
        />
      ))}
    </span>
  );
}

function Scatter({
  points,
  colours,
  hovered,
  onHover,
}: {
  points: ProjectionResult["points"];
  colours: Map<string, string>;
  hovered: string | null;
  onHover: (id: string | null) => void;
}) {
  const W = 720;
  const H = 340;
  const pad = 24;
  const xs = points.map((p) => p.x);
  const ys = points.map((p) => p.y);
  const minX = Math.min(...xs);
  const maxX = Math.max(...xs);
  const minY = Math.min(...ys);
  const maxY = Math.max(...ys);
  // A single point, or a perfectly collinear set, gives a zero-width range; fall back to the
  // centre rather than dividing by zero and rendering NaN coordinates.
  const sx = (v: number) => (maxX === minX ? W / 2 : pad + ((v - minX) / (maxX - minX)) * (W - 2 * pad));
  const sy = (v: number) => (maxY === minY ? H / 2 : H - pad - ((v - minY) / (maxY - minY)) * (H - 2 * pad));

  const active = points.find((p) => p.id === hovered);

  return (
    <div className="scatter-wrap">
      <svg viewBox={`0 0 ${W} ${H}`} className="scatter" role="img" aria-label="Embedding projection">
        {points.map((p) => (
          <circle
            key={p.id}
            cx={sx(p.x)}
            cy={sy(p.y)}
            r={hovered === p.id ? 9 : 6}
            fill={colours.get(p.filename ?? "?")}
            opacity={hovered && hovered !== p.id ? 0.35 : 0.9}
            onMouseEnter={() => onHover(p.id)}
            onMouseLeave={() => onHover(null)}
          />
        ))}
      </svg>
      {active && (
        <div className="scatter-tip">
          <strong>{active.filename}</strong>
          <p className="passage small">{active.excerpt}</p>
        </div>
      )}
    </div>
  );
}
