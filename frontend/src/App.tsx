import { useAuth } from "react-oidc-context";
import { Navigate, Route, Routes } from "react-router-dom";
import { NamespaceProvider } from "./app/NamespaceContext";
import Shell from "./app/Shell";
import NotebookPage from "./pages/NotebookPage";
import VectorsPage from "./pages/VectorsPage";
import AdminPage from "./pages/AdminPage";
import SettingsPage from "./pages/SettingsPage";
import EventsPage from "./pages/EventsPage";
import ApiPage from "./pages/ApiPage";

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

  // Realm roles arrive under realm_access.roles; admin-only tabs are hidden without the role.
  // This is presentation only — the backend enforces the same rule on every request, so a
  // hand-typed URL gets a 403 rather than data.
  const roles: string[] = (auth.user?.profile as never as { realm_access?: { roles?: string[] } })
    ?.realm_access?.roles ?? [];
  const isAdmin = roles.includes("ossian-admin");

  return (
    <NamespaceProvider>
      <Shell isAdmin={isAdmin}>
        <Routes>
          <Route path="/" element={<Navigate to="/notebook" replace />} />
          <Route path="/notebook" element={<NotebookPage />} />
          <Route path="/events" element={<EventsPage />} />
          <Route path="/explorer" element={<ApiPage />} />
          <Route path="/vectors" element={isAdmin ? <VectorsPage /> : <Navigate to="/notebook" replace />} />
          <Route path="/admin" element={isAdmin ? <AdminPage /> : <Navigate to="/notebook" replace />} />
          <Route path="/settings" element={isAdmin ? <SettingsPage /> : <Navigate to="/notebook" replace />} />
          <Route path="*" element={<Navigate to="/notebook" replace />} />
        </Routes>
      </Shell>
    </NamespaceProvider>
  );
}
