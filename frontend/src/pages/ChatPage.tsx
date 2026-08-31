import { useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type AskResponse } from "../api/client";

export default function ChatPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;
  const [question, setQuestion] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<AskResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function ask(e: React.FormEvent) {
    e.preventDefault();
    if (!question.trim()) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.ask(token, question));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel">
      <h2>Ask your documents</h2>
      <form onSubmit={ask} className="ask">
        <textarea
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          placeholder="What does the runbook say about failover?"
          rows={3}
        />
        <button className="primary" disabled={busy || !question.trim()}>
          {busy ? "Thinking…" : "Ask"}
        </button>
      </form>

      {error && <p className="error">{error}</p>}

      {result && (
        <>
          <div className="meta">
            <span className={result.answeredFromContext ? "chip ok" : "chip warn"}>
              {result.answeredFromContext ? "grounded in your documents" : "no supporting context found"}
            </span>
            <span className="chip">{result.latencyMs} ms</span>
            <span className="chip">{result.citations.length} sources</span>
          </div>

          <article className="answer">{result.answer}</article>

          {result.citations.length > 0 && (
            <>
              <h3>Sources</h3>
              <ol className="citations">
                {result.citations.map((c) => (
                  <li key={c.index}>
                    <div className="cite-head">
                      <strong>{c.filename}</strong>
                      {c.score != null && <span className="chip">score {c.score.toFixed(3)}</span>}
                    </div>
                    <p className="excerpt">{c.excerpt}…</p>
                  </li>
                ))}
              </ol>
            </>
          )}
        </>
      )}
    </section>
  );
}
