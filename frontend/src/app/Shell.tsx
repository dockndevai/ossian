import { useState } from "react";
import { useAuth } from "react-oidc-context";
import { NavLink } from "react-router-dom";
import { useNamespace } from "./NamespaceContext";

/**
 * Left navigation, the namespace switcher, and the account strip.
 *
 * The namespace switcher lives here rather than on each page because it applies to all of them:
 * it is the lens the whole app is looking through. The tenant chip beside it is deliberately
 * not a control — tenancy comes from the token, and making it look adjustable would suggest
 * otherwise.
 */

interface Item {
  to: string;
  label: string;
  hint: string;
  admin?: boolean;
}

const ITEMS: { section: string; items: Item[] }[] = [
  {
    section: "Work",
    items: [
      { to: "/notebook", label: "Notebook", hint: "Ask your sources" },
      { to: "/events", label: "Imports", hint: "Event-driven ingestion" },
    ],
  },
  {
    section: "Inspect",
    items: [
      { to: "/vectors", label: "Vectors", hint: "What the retriever sees", admin: true },
      { to: "/admin", label: "Console", hint: "Corpus and retrieval health", admin: true },
    ],
  },
  {
    section: "Build",
    items: [
      { to: "/settings", label: "Settings", hint: "Model, retrieval, ingestion", admin: true },
      { to: "/explorer", label: "API", hint: "Try endpoints as yourself" },
    ],
  },
];

export default function Shell({ isAdmin, children }: { isAdmin: boolean; children: React.ReactNode }) {
  const auth = useAuth();
  const { namespaces, current, setCurrent, create } = useNamespace();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setError(null);
    try {
      await create(name.trim());
      setName("");
      setCreating(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <div className="shell">
      <aside className="nav">
        <div className="brand">Ossian</div>

        <div className="ns">
          <label className="small muted" htmlFor="ns">
            Namespace
          </label>
          <select
            id="ns"
            value={current ?? ""}
            onChange={(e) => setCurrent(e.target.value || null)}
          >
            <option value="">All namespaces</option>
            {namespaces.map((n) => (
              <option key={n.name} value={n.name}>
                {n.name}
              </option>
            ))}
          </select>
          {creating ? (
            <form className="ns-new" onSubmit={submit}>
              <input
                autoFocus
                value={name}
                placeholder="new namespace"
                onChange={(e) => setName(e.target.value)}
              />
              <button className="primary" type="submit">
                Add
              </button>
              <button type="button" onClick={() => setCreating(false)}>
                Cancel
              </button>
            </form>
          ) : (
            <button className="link" onClick={() => setCreating(true)}>
              new namespace
            </button>
          )}
          {error && <p className="error small">{error}</p>}
        </div>

        <nav>
          {ITEMS.map((section) => {
            const visible = section.items.filter((i) => !i.admin || isAdmin);
            if (visible.length === 0) return null;
            return (
              <div className="nav-section" key={section.section}>
                <h4>{section.section}</h4>
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to}>
                    <strong>{item.label}</strong>
                    <span className="small muted">{item.hint}</span>
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>

        <div className="account">
          {/* Not a control: tenancy is decided by the token, not by the UI. */}
          <span className="chip" title="From your token, not chosen here">
            {(auth.user?.profile as never as { tenant?: string })?.tenant ?? "default"}
          </span>
          <span className="small">{auth.user?.profile.preferred_username}</span>
          <button className="link" onClick={() => void auth.signoutRedirect()}>
            sign out
          </button>
        </div>
      </aside>

      <main className="content">{children}</main>
    </div>
  );
}
