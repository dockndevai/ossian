import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type AuditEntry } from "../api/client";

/**
 * Who did what.
 *
 * <p>The filter is a list of the actions that have actually happened rather than every action the
 * code can emit. A filter offering twenty verbs that return nothing is a filter people stop
 * using, and the shape of what is here is itself informative.
 *
 * <p>Machine and person are distinguished in the row, because "was this us or a pipeline" is
 * almost always the first question asked of an entry.
 */
export default function AuditPanel() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [entries, setEntries] = useState<AuditEntry[]>([]);
  const [actions, setActions] = useState<{ action: string; occurrences: number }[]>([]);
  const [filter, setFilter] = useState("");
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [rows, kinds] = await Promise.all([
        api.audit(token, filter || undefined, 100),
        api.auditActions(token),
      ]);
      setEntries(rows);
      setActions(kinds);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }, [token, filter]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <section className="panel">
      <div className="rail-head">
        <h2>Audit</h2>
        <select value={filter} onChange={(e) => setFilter(e.target.value)}>
          <option value="">every action</option>
          {actions.map((a) => (
            <option key={a.action} value={a.action}>
              {a.action} · {a.occurrences}
            </option>
          ))}
        </select>
      </div>
      <p className="muted small">
        Append-only. Recorded as the actor was presented at the time, not as a reference to a user
        — people leave and keys are revoked, and “key:nightly-import” still means something
        afterwards where a dangling reference does not.
      </p>

      {error && <p className="error">{error}</p>}
      {entries.length === 0 && <p className="muted small">Nothing recorded yet.</p>}

      {entries.length > 0 && (
        <table>
          <thead>
            <tr>
              <th>When</th>
              <th>Who</th>
              <th>Action</th>
              <th>Target</th>
              <th>Detail</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((e) => (
              <tr key={e.id}>
                <td className="muted small" title={e.at}>
                  {new Date(e.at).toLocaleTimeString()}
                </td>
                <td>
                  <span className={`chip ${e.machine ? "warn" : ""}`} title={e.subject ?? undefined}>
                    {e.actor}
                  </span>
                </td>
                <td>
                  {e.action}
                  {e.outcome !== "success" && <span className="chip bad">{e.outcome}</span>}
                </td>
                <td className="muted small">
                  {e.targetType}
                  {e.namespace && ` · ${e.namespace}`}
                </td>
                <td className="muted small">{e.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
