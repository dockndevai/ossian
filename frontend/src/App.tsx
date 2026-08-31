import { useAuth } from "react-oidc-context";
import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import NotebookPage from "./pages/NotebookPage";
import VectorsPage from "./pages/VectorsPage";
import AdminPage from "./pages/AdminPage";

export default function App() {
  const auth = useAuth();

  if (auth.isLoading) return <div className="centered">Signing in…</div>;

  if (auth.error) {
    return (
      <div className="centered">
        <h2>Sign-in failed</h2>
        <p className="muted">{auth.error.message}</p>
        <button onClick={() => void auth.signinRedirect()}>Try again</button>
      </div>
    );
  }

  if (!auth.isAuthenticated) {
    return (
      <div className="centered">
        <h1>Ossian</h1>
        <p className="muted">Answers from your own documents, with citations.</p>
        <button className="primary" onClick={() => void auth.signinRedirect()}>
          Sign in with Keycloak
        </button>
      </div>
    );
  }

  // Realm roles arrive under realm_access.roles; the admin tab is hidden without the role.
  // This is presentation only — the backend enforces the same rule independently.
  const roles: string[] = (auth.user?.profile as never as { realm_access?: { roles?: string[] } })
    ?.realm_access?.roles ?? [];
  const isAdmin = roles.includes("ossian-admin");
  const tenant = (auth.user?.profile as never as { tenant?: string })?.tenant ?? "default";

  return (
    <>
      <header>
        <strong>Ossian</strong>
        <nav>
          <NavLink to="/notebook">Notebook</NavLink>
          {isAdmin && <NavLink to="/vectors">Vectors</NavLink>}
          {isAdmin && <NavLink to="/admin">Admin</NavLink>}
        </nav>
        <span className="spacer" />
        <span className="chip" title="Tenant comes from your token, not from the UI">
          {tenant}
        </span>
        <span className="muted">{auth.user?.profile.preferred_username}</span>
        <button onClick={() => void auth.signoutRedirect()}>Sign out</button>
      </header>

      <main>
        <Routes>
          <Route path="/" element={<Navigate to="/notebook" replace />} />
          <Route path="/notebook" element={<NotebookPage />} />
          {/* Hiding a tab is presentation only; the backend enforces the same rule on every
              request, so a hand-typed URL gets a 403 rather than data. */}
          <Route path="/vectors" element={isAdmin ? <VectorsPage /> : <Navigate to="/notebook" replace />} />
          <Route path="/admin" element={isAdmin ? <AdminPage /> : <Navigate to="/notebook" replace />} />
        </Routes>
      </main>
    </>
  );
}
