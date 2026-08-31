import { useState } from "react";
import { useAuth } from "react-oidc-context";
import { NavLink, useLocation } from "react-router-dom";
import Logo from "./Logo";
import { useNamespace } from "./NamespaceContext";
import { ACCENTS, useTheme, type ThemeMode } from "./ThemeContext";
import { NAV, isScoped } from "./nav";

/**
 * Left navigation, the namespace switcher, theme controls and the account strip.
 *
 * Two things here are deliberate rather than incidental. The namespace switcher greys itself out
 * on pages it does not affect, and pages it does affect carry a dot — a global control that
 * silently does nothing on half the app is worse than no control at all.
 */
export default function Shell({ isAdmin, children }: { isAdmin: boolean; children: React.ReactNode }) {
  const auth = useAuth();
  const location = useLocation();
  const { namespaces, current, setCurrent, create } = useNamespace();
  const { mode, setMode, accent, setAccent } = useTheme();

  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);

  const scoped = isScoped(location.pathname);

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
        <div className="brand">
          <Logo size={26} />
          <span>Ossian</span>
        </div>

        <div className={`ns${scoped ? "" : " inactive"}`}>
          <label className="small muted" htmlFor="ns">
            Namespace
          </label>
          <select
            id="ns"
            value={current ?? ""}
            disabled={!scoped}
            title={scoped ? "Filters this page" : "This page is not namespace-scoped"}
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
              <input autoFocus value={name} placeholder="new namespace" onChange={(e) => setName(e.target.value)} />
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

          <p className="muted ns-note">
            {scoped ? "Filters this page." : "Does not apply to this page."}
          </p>
        </div>

        <nav>
          {NAV.map((section) => {
            const visible = section.items.filter((i) => !i.admin || isAdmin);
            if (visible.length === 0) return null;
            return (
              <div className="nav-section" key={section.section}>
                <h4>{section.section}</h4>
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to}>
                    <strong>{item.label}</strong>
                    <span className="small muted">{item.hint}</span>
                    {item.scoped && (
                      <span className="scoped-dot" title="Filtered by the namespace above" />
                    )}
                  </NavLink>
                ))}
              </div>
            );
          })}
        </nav>

        <div className="theme">
          <span className="small muted">Theme</span>
          <div className="seg">
            {(["system", "light", "dark"] as ThemeMode[]).map((m) => (
              <button key={m} className={mode === m ? "on" : undefined} onClick={() => setMode(m)}>
                {m}
              </button>
            ))}
          </div>
          <div className="swatches">
            {ACCENTS.map((a) => (
              <button
                key={a.id}
                className={`swatch${accent === a.id ? " on" : ""}`}
                title={a.label}
                aria-label={`${a.label} accent`}
                style={{ background: a.light }}
                onClick={() => setAccent(a.id)}
              />
            ))}
          </div>
        </div>

        <div className="account">
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
