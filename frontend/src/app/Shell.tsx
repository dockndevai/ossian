import { useAuth } from "react-oidc-context";
import { NavLink, useLocation } from "react-router-dom";
import Logo from "./Logo";
import NamespacePicker from "./NamespacePicker";
import { ACCENTS, useTheme, type ThemeMode } from "./ThemeContext";
import { NAV, isScoped } from "./nav";

/**
 * Left navigation, theme controls and the account strip.
 *
 * The namespace picker sits inside the Work section rather than above the navigation. It governs
 * three pages and not the rest, and a control at the top of a sidebar reads as applying to
 * everything below it — so it was making a promise the app does not keep. Pages it does affect
 * carry a dot; on the others it disables itself and says so.
 */
export default function Shell({ isAdmin, children }: { isAdmin: boolean; children: React.ReactNode }) {
  const auth = useAuth();
  const location = useLocation();
  const { mode, setMode, accent, setAccent } = useTheme();
  const scoped = isScoped(location.pathname);

  return (
    <div className="shell">
      <aside className="nav">
        <div className="brand">
          <Logo size={26} />
          <span>Ossian</span>
        </div>

        <nav>
          {NAV.map((section) => {
            const visible = section.items.filter((i) => !i.admin || isAdmin);
            if (visible.length === 0) return null;
            return (
              <div className="nav-section" key={section.section}>
                <h4>{section.section}</h4>
                {section.section === "Work" && <NamespacePicker active={scoped} />}
                {visible.map((item) => (
                  <NavLink key={item.to} to={item.to}>
                    <strong>{item.label}</strong>
                    <span className="small muted">{item.hint}</span>
                    {item.scoped && (
                      <span className="scoped-dot" title="Filtered by the namespace picker" />
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
