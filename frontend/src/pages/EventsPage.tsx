import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type EventResult, type EventView, type NamespaceView } from "../api/client";

/**
 * The event-driven import feed, and a way to send one by hand.
 *
 * The console exists because an import pipeline fails quietly from the corpus's point of view:
 * documents that never arrived look exactly like documents that were never sent. This shows
 * what the pipeline actually delivered, including the events that were rejected and why.
 */
export default function EventsPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [events, setEvents] = useState<EventView[]>([]);
  const [namespaces, setNamespaces] = useState<NamespaceView[]>([]);
  const [result, setResult] = useState<EventResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [form, setForm] = useState({
    eventId: "",
    operation: "UPSERT" as "UPSERT" | "DELETE",
    externalId: "",
    namespace: "default",
    source: "manual",
    filename: "",
    text: "",
  });

  const load = useCallback(async () => {
    try {
      const [page, ns] = await Promise.all([api.ingestEvents(token), api.namespaces(token)]);
      setEvents(page.content);
      setNamespaces(ns);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  async function send(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.sendEvent(token, { ...form, filename: form.filename || undefined }));
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="stack">
      <section className="panel">
        <h2>Send an event</h2>
        <p className="muted">
          The same endpoint a CDC pipeline or webhook calls. Send the same event id twice and the
          second is recognised as a duplicate rather than producing a second document — which is
          the property that makes at-least-once delivery safe.
        </p>

        <form className="grid-form" onSubmit={send}>
          <label>
            Event id
            <input
              required
              value={form.eventId}
              placeholder="crm-4172-v2"
              onChange={(e) => setForm({ ...form, eventId: e.target.value })}
            />
            <span className="muted small">Unique per tenant. This is the idempotency key.</span>
          </label>

          <label>
            Operation
            <select
              value={form.operation}
              onChange={(e) => setForm({ ...form, operation: e.target.value as "UPSERT" | "DELETE" })}
            >
              <option value="UPSERT">UPSERT</option>
              <option value="DELETE">DELETE</option>
            </select>
          </label>

          <label>
            External id
            <input
              required
              value={form.externalId}
              placeholder="crm/4172"
              onChange={(e) => setForm({ ...form, externalId: e.target.value })}
            />
            <span className="muted small">Its identity in the source system, not ours.</span>
          </label>

          <label>
            Namespace
            <select
              value={form.namespace}
              onChange={(e) => setForm({ ...form, namespace: e.target.value })}
            >
              {namespaces.map((n) => (
                <option key={n.name} value={n.name}>
                  {n.name}
                </option>
              ))}
            </select>
          </label>

          <label>
            Source
            <input value={form.source} onChange={(e) => setForm({ ...form, source: e.target.value })} />
          </label>

          <label>
            Filename
            <input
              value={form.filename}
              placeholder="falls back to the external id"
              onChange={(e) => setForm({ ...form, filename: e.target.value })}
            />
          </label>

          <label className="wide">
            Text
            <textarea
              rows={5}
              value={form.text}
              placeholder="Document body. A real pipeline may send contentBase64 instead, for PDFs and anything else Tika has to parse."
              onChange={(e) => setForm({ ...form, text: e.target.value })}
            />
          </label>

          <div className="wide row">
            <button className="primary" disabled={busy}>
              {busy ? "…" : "Send event"}
            </button>
            {result && (
              <span className={`chip ${result.status === "FAILED" ? "bad" : result.status === "DUPLICATE" ? "warn" : "ok"}`}>
                {result.status}
                {result.message ? ` — ${result.message}` : ""}
              </span>
            )}
            {error && <span className="error small">{error}</span>}
          </div>
        </form>
      </section>

      <section className="panel">
        <h2>Delivered events</h2>
        <p className="muted">What the pipeline sent, for reconciling against the source system.</p>
        <table>
          <thead>
            <tr>
              <th>Event id</th>
              <th>Op</th>
              <th>External id</th>
              <th>Namespace</th>
              <th>Source</th>
              <th>Status</th>
              <th>When</th>
            </tr>
          </thead>
          <tbody>
            {events.map((e) => (
              <tr key={e.eventId}>
                <td><code className="small">{e.eventId}</code></td>
                <td>{e.operation}</td>
                <td><code className="small">{e.externalId}</code></td>
                <td>{e.namespace}</td>
                <td>{e.source ?? "—"}</td>
                <td>
                  <span className={`chip ${e.status === "ACCEPTED" ? "ok" : e.status === "FAILED" ? "bad" : "warn"}`}>
                    {e.status.toLowerCase()}
                  </span>
                  {e.errorMessage && <p className="error small">{e.errorMessage}</p>}
                </td>
                <td className="muted small">{new Date(e.createdAt).toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {events.length === 0 && <p className="muted">No events yet.</p>}
      </section>
    </div>
  );
}
