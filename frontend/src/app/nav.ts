/**
 * The navigation model, and which pages the namespace switcher actually affects.
 *
 * Kept in one place because it is read twice — once to render the menu, once to decide whether
 * the switcher is live on the current route. Two lists would drift, and the failure mode is a
 * control that appears to work and does nothing.
 */
export interface NavItem {
  to: string;
  label: string;
  hint: string;
  admin?: boolean;
  /** Whether the namespace switcher filters this page. */
  scoped: boolean;
}

export const NAV: { section: string; items: NavItem[] }[] = [
  {
    section: "Work",
    items: [
      { to: "/notebook", label: "Notebook", hint: "Ask your sources", scoped: true },
      // The form picks its own namespace per event, and the feed is a record of what a pipeline
      // sent across all of them — narrowing it would hide deliveries rather than focus them.
      { to: "/events", label: "Imports", hint: "Event-driven ingestion", scoped: false },
    ],
  },
  {
    section: "Inspect",
    items: [
      { to: "/vectors", label: "Vectors", hint: "What the retriever sees", admin: true, scoped: true },
      { to: "/admin", label: "Console", hint: "Corpus and retrieval health", admin: true, scoped: true },
    ],
  },
  {
    section: "Build",
    items: [
      // Settings are per tenant, not per namespace: one model and one threshold serve them all.
      { to: "/settings", label: "Settings", hint: "Model, retrieval, ingestion", admin: true, scoped: false },
      { to: "/explorer", label: "API", hint: "Try endpoints as yourself", scoped: false },
      { to: "/about", label: "About", hint: "What this is, and how it behaves", scoped: false },
    ],
  },
];

const SCOPED = new Set(NAV.flatMap((s) => s.items).filter((i) => i.scoped).map((i) => i.to));

export function isScoped(pathname: string): boolean {
  return SCOPED.has(pathname);
}
