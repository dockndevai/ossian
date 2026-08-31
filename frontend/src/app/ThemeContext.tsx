import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

/**
 * Theme, chosen by the viewer and remembered on their machine.
 *
 * Three states, not two. "System" is the default and is not the same as light: it follows the
 * operating system and keeps following it when the user changes it at dusk. A two-way toggle
 * quietly makes that impossible, which is why the stored value distinguishes "no choice made"
 * from "chose light".
 *
 * The accent is separate because it is not a light/dark decision — it survives switching
 * between them.
 */

export type ThemeMode = "system" | "light" | "dark";

export const ACCENTS = [
  { id: "indigo", label: "Indigo", light: "#4f46e5", dark: "#818cf8" },
  { id: "teal", label: "Teal", light: "#0f766e", dark: "#2dd4bf" },
  { id: "amber", label: "Amber", light: "#b45309", dark: "#fbbf24" },
  { id: "rose", label: "Rose", light: "#be123c", dark: "#fb7185" },
] as const;

export type AccentId = (typeof ACCENTS)[number]["id"];

interface ThemeState {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  accent: AccentId;
  setAccent: (accent: AccentId) => void;
  /** What is actually on screen right now, with "system" resolved. */
  resolved: "light" | "dark";
}

const Ctx = createContext<ThemeState | null>(null);

const MODE_KEY = "ossian.theme";
const ACCENT_KEY = "ossian.accent";

function read(key: string, fallback: string) {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    // Private browsing and blocked site data both throw on access, not just on write.
    return fallback;
  }
}

function write(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    /* a forgotten preference is a small loss; a crash is not */
  }
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(() => {
    const stored = read(MODE_KEY, "system");
    return stored === "light" || stored === "dark" ? stored : "system";
  });
  const [accent, setAccentState] = useState<AccentId>(() => {
    const stored = read(ACCENT_KEY, "indigo");
    return (ACCENTS.some((a) => a.id === stored) ? stored : "indigo") as AccentId;
  });

  const [systemDark, setSystemDark] = useState(
    () => typeof matchMedia === "function" && matchMedia("(prefers-color-scheme: dark)").matches,
  );

  // Keep following the system while the mode is "system": someone whose machine switches at
  // sunset should see the app switch with it, without reloading.
  useEffect(() => {
    if (typeof matchMedia !== "function") return;
    const query = matchMedia("(prefers-color-scheme: dark)");
    const onChange = (e: MediaQueryListEvent) => setSystemDark(e.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  const resolved: "light" | "dark" = mode === "system" ? (systemDark ? "dark" : "light") : mode;

  useEffect(() => {
    const root = document.documentElement;
    if (mode === "system") root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", mode);
    root.setAttribute("data-accent", accent);
  }, [mode, accent]);

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next);
    write(MODE_KEY, next);
  }, []);

  const setAccent = useCallback((next: AccentId) => {
    setAccentState(next);
    write(ACCENT_KEY, next);
  }, []);

  const value = useMemo(
    () => ({ mode, setMode, accent, setAccent, resolved }),
    [mode, setMode, accent, setAccent, resolved],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useTheme() {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useTheme must be used inside ThemeProvider");
  return ctx;
}
