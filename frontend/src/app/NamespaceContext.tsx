import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api, type NamespaceView } from "../api/client";

/**
 * The namespace currently in view, shared by every page.
 *
 * It is app state rather than a prop because the choice outlives the page: picking a namespace
 * in the notebook and then opening the vector view should not silently reset it to everything.
 * The selection is remembered per browser, not per tenant — the tenant comes from the token and
 * is not the UI's to choose.
 */

interface NamespaceState {
  namespaces: NamespaceView[];
  current: string | null;
  setCurrent: (name: string | null) => void;
  refresh: () => Promise<void>;
  create: (name: string, description?: string) => Promise<void>;
}

const Ctx = createContext<NamespaceState | null>(null);

const STORAGE_KEY = "ossian.namespace";

export function NamespaceProvider({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [namespaces, setNamespaces] = useState<NamespaceView[]>([]);
  const [current, setCurrentState] = useState<string | null>(() => {
    try {
      return sessionStorage.getItem(STORAGE_KEY);
    } catch {
      // Private browsing and blocked site data both throw here. A forgotten selection is a
      // small loss; a page that will not render is not.
      return null;
    }
  });

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      setNamespaces(await api.namespaces(token));
    } catch {
      setNamespaces([]);
    }
  }, [token]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const setCurrent = useCallback((name: string | null) => {
    setCurrentState(name);
    try {
      if (name) sessionStorage.setItem(STORAGE_KEY, name);
      else sessionStorage.removeItem(STORAGE_KEY);
    } catch {
      /* see above */
    }
  }, []);

  const create = useCallback(
    async (name: string, description?: string) => {
      if (!token) return;
      const created = await api.createNamespace(token, name, description);
      await refresh();
      setCurrent(created.name);
    },
    [token, refresh, setCurrent],
  );

  const value = useMemo(
    () => ({ namespaces, current, setCurrent, refresh, create }),
    [namespaces, current, setCurrent, refresh, create],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useNamespace() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useNamespace must be used inside NamespaceProvider");
  return ctx;
}
