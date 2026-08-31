import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type AgentSummary, type Memory } from "../api/client";

/**
 * What the agents built on this remember.
 *
 * <p>Two questions an operator actually has, and the page answers them in that order: which
 * agents are holding memory, and what does this one think it knows. The second matters more than
 * it sounds — an agent behaving oddly is usually an agent recalling something stale, and until
 * you can read the memory back there is nothing to look at but the output.
 *
 * <p>The recall box runs the real ranking rather than filtering the list on screen. Text search
 * would answer a different question from the one the agent asks, and the useful thing to see is
 * what the agent would actually get.
 */
export default function MemoryPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [memories, setMemories] = useState<Memory[]>([]);
  const [query, setQuery] = useState("");
  const [recalled, setRecalled] = useState<Memory[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadAgents = useCallback(async () => {
    try {
      const list = await api.agents(token);
      setAgents(list);
      setSelected((current) => current ?? list[0]?.agentId ?? null);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token]);

  useEffect(() => {
    void loadAgents();
  }, [loadAgents]);

  const loadMemories = useCallback(async () => {
    if (!selected) return;
    try {
      setMemories(await api.memories(token, selected));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token, selected]);

  useEffect(() => {
    setRecalled(null);
    setQuery("");
    void loadMemories();
  }, [loadMemories]);

  async function recall(e: React.FormEvent) {
    e.preventDefault();
    if (!selected || !query.trim()) return;
    setBusy(true);
    setError(null);
    try {
      setRecalled(await api.recall(token, selected, query.trim()));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  async function forget(id: string) {
    await api.forgetMemory(token, id);
    setRecalled((prev) => prev?.filter((m) => m.id !== id) ?? null);
    setMemories((prev) => prev.filter((m) => m.id !== id));
    void loadAgents();
  }

  const shown = recalled ?? memories;

  return (
    <div className="stack">
      <section className="panel">
        <h2>Agent memory</h2>
        <p className="muted">
          What agents have recorded and can recall. Separate from the document corpus: memory is
          what an agent was told or worked out, and it is never returned as a citation in an
          answer about your documents.
        </p>

        {agents.length === 0 && (
          <p className="muted small">
            Nothing yet. An agent writes here with <code>POST /api/memory</code>; there is a
            worked example on the API page.
          </p>
        )}

        {agents.length > 0 && (
          <div className="agent-tabs">
            {agents.map((a) => (
              <button
                key={a.agentId}
                className={a.agentId === selected ? "on" : undefined}
                onClick={() => setSelected(a.agentId)}
              >
                <strong>{a.agentId}</strong>
                <span className="muted small">
                  {a.memories} {a.memories === 1 ? "memory" : "memories"}
                  {a.sessions > 0 && ` · ${a.sessions} ${a.sessions === 1 ? "session" : "sessions"}`}
                  {a.expiring > 0 && ` · ${a.expiring} expiring`}
                </span>
              </button>
            ))}
          </div>
        )}
      </section>

      {selected && (
        <section className="panel">
          <h2>Recall</h2>
          <p className="muted small">
            Runs the agent's own ranking — similarity, weighted by importance and decayed by age —
            rather than searching the text on this page. What you see is what the agent would get.
          </p>
          <form className="ask" onSubmit={recall}>
            <input
              value={query}
              placeholder="what would the agent remember about…"
              onChange={(e) => setQuery(e.target.value)}
            />
            <div className="row-actions">
              <button className="primary" disabled={busy || !query.trim()}>
                {busy ? "…" : "Recall"}
              </button>
              {recalled && (
                <button type="button" onClick={() => { setRecalled(null); setQuery(""); }}>
                  Show everything
                </button>
              )}
            </div>
          </form>
          {error && <p className="error">{error}</p>}
        </section>
      )}

      {selected && (
        <section className="panel">
          <h2>
            {recalled ? `Recalled — ${shown.length}` : `Everything ${selected} holds — ${shown.length}`}
          </h2>

          {shown.length === 0 && (
            <p className="muted small">
              {recalled
                ? "Nothing cleared the similarity floor. That is the honest answer, not an empty one."
                : "This agent holds no memories."}
            </p>
          )}

          <ul className="memories">
            {shown.map((m) => (
              <li key={m.id}>
                <p className="memory-text">{m.content}</p>
                <div className="meta">
                  <span className="chip">{m.kind}</span>
                  {m.subject && <span className="chip ns-chip">{m.subject}</span>}
                  {m.sessionId && <span className="chip">session {m.sessionId}</span>}
                  {m.importance !== 1 && (
                    <span className="chip warn" title="Weighting the agent set">
                      importance {m.importance.toFixed(1)}
                    </span>
                  )}
                  {m.score != null && (
                    <span className="chip ok" title="Similarity × importance × age decay">
                      score {m.score.toFixed(3)}
                    </span>
                  )}
                  {m.similarity != null && (
                    <span className="chip">similarity {m.similarity.toFixed(3)}</span>
                  )}
                  {m.expiresAt && (
                    <span className="chip warn" title={m.expiresAt}>
                      expires
                    </span>
                  )}
                  <span className="spacer" />
                  <span className="muted small" title={m.createdAt}>
                    used {m.useCount}×
                  </span>
                  <button className="link" onClick={() => void forget(m.id)}>
                    forget
                  </button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
