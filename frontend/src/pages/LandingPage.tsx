import { useAuth } from "react-oidc-context";
import Logo from "../app/Logo";

/**
 * The page you land on before signing in.
 *
 * It says what the thing is and what it refuses to do, because the second half is the part
 * people get wrong about retrieval systems: the value is not that it answers, it is that it
 * declines to when the corpus cannot support an answer. Someone arriving here should be able to
 * decide whether this is the tool they want without signing in first.
 */
export default function LandingPage() {
  const auth = useAuth();

  return (
    <div className="landing">
      <header className="landing-hero">
        <span className="mark">
          <Logo size={72} />
        </span>
        <h1>Ossian</h1>
        <p className="lede">
          Open-book answers over your own documents. Every claim carries a citation you can open,
          and when nothing in the corpus supports an answer it says so instead of inventing one.
        </p>
        <button className="primary big" onClick={() => void auth.signinRedirect()}>
          Sign in with Keycloak
        </button>
        {auth.error && <p className="error">{auth.error.message}</p>}
      </header>

      <section className="landing-section">
        <h2>How it works</h2>
        <ol className="steps">
          <li>
            <strong>Ingest</strong>
            <p>
              Drop in PDFs, Word files, Markdown or plain text. Tika reads the format from the
              bytes, so nothing has to be converted first.
            </p>
          </li>
          <li>
            <strong>Split and embed</strong>
            <p>
              Documents are cut into overlapping passages and embedded into pgvector. The overlap
              matters: without it a fact that straddles a boundary belongs to neither passage and
              is never retrieved.
            </p>
          </li>
          <li>
            <strong>Retrieve</strong>
            <p>
              A question is embedded and matched against the corpus, filtered to your tenant and,
              if you choose, one namespace. Weak matches are discarded rather than passed on.
            </p>
          </li>
          <li>
            <strong>Answer, with sources</strong>
            <p>
              Only the retrieved passages go to the model, numbered so its <code>[1]</code>
              markers map back to real documents you can open and check.
            </p>
          </li>
        </ol>
      </section>

      <section className="landing-section">
        <h2>What you get</h2>
        <div className="feature-grid">
          <Feature title="Grounded, or silent">
            An answer is written only from retrieved passages. Ask something the corpus does not
            cover and you get a refusal, not a plausible paragraph.
          </Feature>
          <Feature title="Tenant isolation">
            Tenancy comes from a claim in your token, never from a header or a parameter. Every
            document, chunk and query is filtered by it, so a client cannot name its own tenant.
          </Feature>
          <Feature title="Namespaces">
            Partition a corpus into slices — handbooks, runbooks, policies — and ask within one
            when a question belongs to one.
          </Feature>
          <Feature title="See the retriever">
            Inspect the stored chunks, run a query with no model in the loop, and view the corpus
            projected to two dimensions. Retrieval failures and generation failures look identical
            from the outside and have opposite fixes.
          </Feature>
          <Feature title="Tunable without a redeploy">
            Model, temperature, chunk size, overlap, how many passages and how good a match has to
            be — all editable per tenant, at runtime.
          </Feature>
          <Feature title="Fed by pipelines">
            An event API for CDC streams and webhooks: idempotent on a caller-assigned event id,
            addressed by the document's identity in your own system.
          </Feature>
        </div>
      </section>

      <section className="landing-section">
        <h2>Built on</h2>
        <p className="muted">
          Spring Boot and Spring AI, Postgres with pgvector, Redis, Keycloak for OAuth 2.0, and
          React with Vite. Model calls go through{" "}
          <a href="https://github.com/dockndevai/spring-llm-gateway" target="_blank" rel="noreferrer">
            spring-llm-gateway
          </a>
          , which owns virtual keys, per-tenant token quotas and usage metering — so Ossian holds
          no upstream model credentials of its own.
        </p>
      </section>

      <footer className="landing-foot muted small">
        Ossian · open-book retrieval ·{" "}
        <a href="https://github.com/dockndevai/ossian" target="_blank" rel="noreferrer">
          source
        </a>
      </footer>
    </div>
  );
}

function Feature({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="feature">
      <strong>{title}</strong>
      <p className="muted">{children}</p>
    </div>
  );
}
