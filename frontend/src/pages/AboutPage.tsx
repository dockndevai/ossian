import { useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { api } from "../api/client";
import Logo from "../app/Logo";

/**
 * What this instance is, and what it decided on your behalf.
 *
 * Deliberately more than a version string. The behaviours worth documenting here are the ones
 * that surprise people — that a question outside the corpus gets refused, that tenancy is not
 * something the UI can choose, that changing chunk size does not retroactively re-cut existing
 * documents. Each is defensible; none is guessable.
 */
export default function AboutPage() {
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [documents, setDocuments] = useState<number | null>(null);
  const [namespaces, setNamespaces] = useState<number | null>(null);

  useEffect(() => {
    // Both endpoints are open to any signed-in user, so this page works without the admin role.
    void (async () => {
      try {
        const [docs, ns] = await Promise.all([api.documents(token, 0), api.namespaces(token)]);
        setDocuments(docs.totalElements);
        setNamespaces(ns.length);
      } catch {
        /* the page is still worth reading without the live numbers */
      }
    })();
  }, [token]);

  const profile = auth.user?.profile as never as { tenant?: string; realm_access?: { roles?: string[] } };
  const roles = profile?.realm_access?.roles?.filter((r) => r.startsWith("ossian-")) ?? [];

  return (
    <div className="stack">
      <section className="panel about-hero">
        <span className="mark">
          <Logo size={52} />
        </span>
        <div>
          <h2>Ossian</h2>
          <p className="muted">
            Open-book retrieval over your own documents. Answers are written only from passages
            the retriever found, each one cited and openable.
          </p>
        </div>
      </section>

      <section className="panel">
        <h2>This session</h2>
        <div className="cards">
          <Fact label="Signed in as" value={auth.user?.profile.preferred_username ?? "—"} />
          <Fact label="Tenant" value={profile?.tenant ?? "default"} />
          <Fact label="Roles" value={roles.length ? roles.join(", ") : "none"} />
          <Fact label="Documents" value={documents ?? "—"} />
          <Fact label="Namespaces" value={namespaces ?? "—"} />
        </div>
        <p className="muted small">
          Your tenant comes from the <code>tenant</code> claim in your token — not from anything
          chosen here. It is the isolation boundary: every document, chunk and query is filtered
          by it, so no request can reach another tenant's corpus whatever it asks for.
        </p>
      </section>

      <section className="panel">
        <h2>How it behaves</h2>
        <dl className="behaviours">
          <dt>It refuses rather than guesses</dt>
          <dd>
            If nothing clears the similarity threshold, you get "I could not find anything about
            that in your documents" instead of an answer. An unsupported answer is worse than no
            answer, because it is indistinguishable from a good one.
          </dd>

          <dt>Citations are the passages, not a summary of them</dt>
          <dd>
            Each citation shows the exact text handed to the model. That is what makes an answer
            checkable rather than merely confident.
          </dd>

          <dt>A namespace narrows; the tenant confines</dt>
          <dd>
            You may read across your own namespaces freely and can never read another tenant's.
            Selecting no namespace searches all of yours — an absent filter widens within the
            tenant, never past it.
          </dd>

          <dt>Chunking changes are not retroactive</dt>
          <dd>
            Editing chunk size or overlap affects documents indexed afterwards. Existing chunks
            keep their old shape until you reindex, which is why those settings are flagged. The
            alternative — silently mixing two chunking strategies in one corpus — is worse.
          </dd>

          <dt>Ingestion is asynchronous</dt>
          <dd>
            An upload returns immediately and the document reports <code>pending</code> until its
            chunks are embedded. Large files take a while; the page follows along on its own.
          </dd>

          <dt>Redelivered events are recognised</dt>
          <dd>
            The import API keys on a caller-assigned event id, so a pipeline that resends after a
            crash does not produce a second copy. At-least-once delivery is the normal case.
          </dd>
        </dl>
      </section>

      <section className="panel">
        <h2>Under it</h2>
        <table>
          <tbody>
            <Row k="Service" v="Spring Boot 3.5, Java 21, Spring AI 1.0" />
            <Row k="Store" v="Postgres 17 with pgvector — HNSW index, cosine distance" />
            <Row k="Cache" v="Redis, for the retrieval cache and rate accounting" />
            <Row k="Identity" v="Keycloak — OAuth 2.0 and OIDC, authorization code with PKCE" />
            <Row k="Console" v="React 19, Vite, TypeScript" />
            <Row k="Models" v="Reached through spring-llm-gateway, which owns keys, quotas and metering" />
            <Row k="Migrations" v="Flyway — the schema is owned by migrations, never by Hibernate" />
          </tbody>
        </table>
        <p className="muted small">
          The gateway matters more than it looks: Ossian holds no upstream model credentials of
          its own, so swapping a model — or a whole provider — is a routing change there rather
          than a deployment here.
        </p>
      </section>

      <section className="panel">
        <h2>Elsewhere</h2>
        <ul className="links">
          <li>
            <a href="/swagger-ui.html" target="_blank" rel="noreferrer">
              OpenAPI description
            </a>{" "}
            <span className="muted small">— the full schema</span>
          </li>
          <li>
            <a href="/explorer">API console</a>{" "}
            <span className="muted small">— send requests as yourself, with your own roles</span>
          </li>
          <li>
            <a href="https://github.com/dockndevai/ossian" target="_blank" rel="noreferrer">
              Source
            </a>
          </li>
          <li>
            <a href="https://github.com/dockndevai/spring-llm-gateway" target="_blank" rel="noreferrer">
              spring-llm-gateway
            </a>{" "}
            <span className="muted small">— the model gateway this talks to</span>
          </li>
        </ul>
      </section>
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="card">
      <span className="label">{label}</span>
      <span className="value small-value">{value}</span>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <tr>
      <th style={{ width: "22%" }}>{k}</th>
      <td>{v}</td>
    </tr>
  );
}
