import { useState } from "react";
import { useNamespace } from "./NamespaceContext";

/**
 * Which slice of the corpus you are working in.
 *
 * <p>Lives inside the Work section rather than above the navigation, because it is not a global
 * control — it changes what three of the pages show and nothing on the rest. Sitting at the top
 * of the sidebar it read as an app-wide setting, which is exactly the impression to avoid.
 *
 * <p>Counts are shown next to each name. A picker listing only names cannot answer the question
 * people actually bring to it, which is which namespace their documents are in; with counts an
 * empty one is visibly empty instead of a guess.
 *
 * <p>When the current page ignores the namespace the control is disabled and says so once.
 * Nothing is said in the common case, where the dots on the scoped pages already carry it —
 * a line of explanation shown permanently is noise that stops being read.
 */
export default function NamespacePicker({ active }: { active: boolean }) {
  const { namespaces, current, setCurrent, create } = useNamespace();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const total = namespaces.reduce((sum, n) => sum + n.documents, 0);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const wanted = name.trim();
    if (!wanted || busy) return;
    setBusy(true);
    setError(null);
    try {
      await create(wanted);
      // Switch to what was just made. Creating a namespace and staying where you were is the
      // kind of nothing-happened that makes people click the button twice.
      setCurrent(wanted);
      setName("");
      setCreating(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`picker${active ? "" : " inert"}`}>
      <div className="picker-row">
        <select
          aria-label="Namespace"
          value={current ?? ""}
          disabled={!active}
          title={active ? "Which namespace this page shows" : "This page is not namespace-scoped"}
          onChange={(e) => setCurrent(e.target.value || null)}
        >
          <option value="">All namespaces · {total}</option>
          {namespaces.map((n) => (
            <option key={n.name} value={n.name}>
              {n.name} · {n.documents}
            </option>
          ))}
        </select>
        <button
          className="picker-add"
          type="button"
          disabled={!active}
          title="New namespace"
          aria-label="New namespace"
          onClick={() => setCreating((v) => !v)}
        >
          {creating ? "×" : "+"}
        </button>
      </div>

      {creating && (
        <form className="picker-new" onSubmit={submit}>
          <input
            autoFocus
            value={name}
            placeholder="name"
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Escape") setCreating(false);
            }}
          />
          <button className="primary" type="submit" disabled={busy || !name.trim()}>
            {busy ? "…" : "Add"}
          </button>
        </form>
      )}

      {error && <p className="error small">{error}</p>}
      {!active && <p className="muted picker-note">Not used on this page.</p>}
    </div>
  );
}
