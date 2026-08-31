import { useCallback, useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type SettingView } from "../api/client";

/**
 * Runtime configuration, per tenant.
 *
 * Each field shows the file default beside the value in force, so it is always clear whether
 * you are looking at a deliberate choice or an inherited one. Clearing a field means "go back
 * to the default" rather than "set it to empty" — the only reading that lets someone undo a
 * change they regret without knowing what the original was.
 */

const GROUPS: { id: string; title: string; blurb: string }[] = [
  {
    id: "model",
    title: "Model",
    blurb:
      "Which model answers, and how freely. These go to the gateway, so the model name must match a route it has.",
  },
  {
    id: "retrieval",
    title: "Retrieval",
    blurb:
      "How much context reaches the model, and how good a match has to be to count. This is the dial between refusing too often and answering from weak evidence.",
  },
  {
    id: "ingestion",
    title: "Ingestion",
    blurb:
      "How documents are cut up before embedding. Changing these affects documents indexed afterwards; existing chunks stay as they are until reindexed.",
  },
];

export default function SettingsPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [settings, setSettings] = useState<SettingView[]>([]);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState<string | null>(null);

  const load = useCallback(async () => {
    const rows = await api.settings(token);
    setSettings(rows);
    setDrafts(Object.fromEntries(rows.map((r) => [r.key, r.effective])));
  }, [token]);

  useEffect(() => {
    void load();
  }, [load]);

  async function save(row: SettingView) {
    setBusy(row.key);
    setErrors((e) => ({ ...e, [row.key]: "" }));
    try {
      const rows = await api.updateSetting(token, row.key, drafts[row.key] ?? "");
      setSettings(rows);
      setSaved(row.key);
      setTimeout(() => setSaved(null), 2000);
    } catch (err) {
      setErrors((e) => ({ ...e, [row.key]: err instanceof Error ? err.message : String(err) }));
    } finally {
      setBusy(null);
    }
  }

  async function reset(row: SettingView) {
    setBusy(row.key);
    try {
      const rows = await api.resetSetting(token, row.key);
      setSettings(rows);
      setDrafts((d) => ({ ...d, [row.key]: rows.find((r) => r.key === row.key)?.effective ?? "" }));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="stack">
      {GROUPS.map((group) => {
        const rows = settings.filter((s) => s.group === group.id);
        if (rows.length === 0) return null;
        return (
          <section className="panel" key={group.id}>
            <h2>{group.title}</h2>
            <p className="muted">{group.blurb}</p>

            <div className="settings">
              {rows.map((row) => {
                const dirty = (drafts[row.key] ?? "") !== row.effective;
                return (
                  <div className="setting" key={row.key}>
                    <div className="setting-head">
                      <label htmlFor={row.key}>{row.label}</label>
                      {row.override != null && <span className="chip warn">overridden</span>}
                      {row.requiresReindex && (
                        <span className="chip" title="Existing chunks keep their old shape until you reindex">
                          reindex to apply
                        </span>
                      )}
                    </div>
                    <p className="muted small">{row.help}</p>

                    {row.type === "TEXT" ? (
                      <textarea
                        id={row.key}
                        rows={6}
                        value={drafts[row.key] ?? ""}
                        onChange={(e) => setDrafts((d) => ({ ...d, [row.key]: e.target.value }))}
                      />
                    ) : (
                      <input
                        id={row.key}
                        type={row.type === "STRING" ? "text" : "number"}
                        step={row.type === "DOUBLE" ? "0.05" : "1"}
                        min={row.min ?? undefined}
                        max={row.max ?? undefined}
                        value={drafts[row.key] ?? ""}
                        onChange={(e) => setDrafts((d) => ({ ...d, [row.key]: e.target.value }))}
                      />
                    )}

                    {errors[row.key] && <p className="error small">{errors[row.key]}</p>}

                    <div className="setting-foot">
                      <span className="muted small">
                        default <code>{truncate(row.defaultValue)}</code>
                        {row.updatedBy && ` · changed by ${row.updatedBy}`}
                      </span>
                      <span className="spacer" />
                      {saved === row.key && <span className="chip ok">saved</span>}
                      {row.override != null && (
                        <button disabled={busy === row.key} onClick={() => void reset(row)}>
                          Reset
                        </button>
                      )}
                      <button
                        className="primary"
                        disabled={busy === row.key || !dirty}
                        onClick={() => void save(row)}
                      >
                        {busy === row.key ? "…" : "Save"}
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function truncate(v: string) {
  return v.length > 48 ? `${v.slice(0, 48)}…` : v;
}
