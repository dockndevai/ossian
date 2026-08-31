# Ossian

Answers from **your own documents**, with citations you can check — plus the maintenance side
that keeps retrieval honest over time.

Two surfaces, one Spring Boot service:

- **Ask** — upload PDFs, runbooks, manuals; ask questions; get an answer grounded in retrieved
  chunks, with the sources it used. If nothing relevant is found it says so instead of guessing.
- **Maintain** — ingestion jobs, re-indexing, corpus stats, and the questions your corpus
  *couldn't* answer. That last one is the most useful screen in the app.

Java 21 · Spring Boot 3.5.3 · Spring AI 1.0.0 · Postgres + pgvector · Keycloak · Redis · React 19

---

## Architecture

```
React 19 + Vite            Spring Boot 3.5                 Postgres 17
┌──────────────┐  bearer  ┌────────────────────┐          ┌──────────────────┐
│ Ask / Docs   │─────────▶│ /api/chat          │─────────▶│ documents        │
│ Admin        │  token   │ /api/documents     │          │ ingestion_jobs   │
└──────┬───────┘          │ /api/admin         │          │ query_log        │
       │ OIDC (PKCE)      └─────────┬──────────┘          │ vector_store ◀── pgvector
       ▼                            │                      └──────────────────┘
┌──────────────┐                    │ OpenAI-compatible
│  Keycloak    │                    ▼
│  realm:      │          ┌────────────────────┐   virtual keys, token quotas,
│  ossian    │          │ spring-llm-gateway │   usage metering, failover
└──────────────┘          └─────────┬──────────┘
                                    ▼
                            Ollama / vLLM / NVIDIA
```

The service holds **no upstream model credentials**. It speaks the OpenAI API to
[spring-llm-gateway](https://github.com/dockndevai/spring-llm-gateway), which owns routing,
per-tenant token quotas and metering. Point `LLM_BASE_URL` straight at Ollama to cut it out.

---

## Quick start

The gateway ships as a container image. Build it once from the
[spring-llm-gateway](https://github.com/dockndevai/spring-llm-gateway) checkout:

```bash
docker build -t spring-llm-gateway:0.1.0 .
```

Then bring the whole stack up:

```bash
docker compose up -d
```

```bash
docker compose exec ollama ollama pull nomic-embed-text
```

```bash
docker compose exec ollama ollama pull qwen2.5:3b
```

Backend:

```bash
./mvnw -pl backend spring-boot:run
```

Frontend:

```bash
cd frontend && npm install && npm run dev
```

Open <http://localhost:5173> and sign in. The realm is imported automatically:

| user | password | tenant | roles |
|---|---|---|---|
| `admin` | `admin` | acme | user + admin |
| `user` | `user` | acme | user |
| `other` | `other` | globex | user + admin |

`other` exists to demonstrate tenant isolation — sign in as it and the corpus is empty, because
tenancy comes from the token.

Ports avoid the usual defaults so this runs beside other local services: backend **8081**,
Postgres **5433**, Keycloak **8180**, Redis **6380**, LLM gateway **8090**, Ollama **11435**.

Inside the compose network services address each other by name — the backend reaches the
gateway at `http://llm-gateway:8080`, and the gateway reaches Ollama at `http://ollama:11434`.
A container's `localhost` is its own, which is the usual reason a working local config breaks
the moment it is containerised.

Don't want the gateway? Set `LLM_BASE_URL=http://ollama:11434` on the backend and it talks to
Ollama directly — you lose virtual keys, quotas and metering, nothing else.

The gateway image ships the gateway project's own sample routes, which match chat models only.
`gateway/application-ossian.yml` is mounted over them to add the embedding model; without it
ingestion gets a 404 from the gateway before it ever reaches Ollama. Compose lists are replaced
rather than merged, so that file restates the chat route too.

---

## The parts worth knowing

### Tenancy is a token claim, never a parameter

`TenantContext` reads the `tenant` claim from the validated JWT. Every query, every retrieval
filter and every stat is scoped by it. There is deliberately no `findById(id)` exposed on the
document repository — knowing an id must not be enough to read another tenant's document, so
lookups are always `findByIdAndTenantId`, and a foreign id returns **404 rather than 403** so the
response doesn't even confirm the document exists.

### Retrieval refuses rather than guesses

Below `ossian.retrieval.similarity-threshold` (default 0.5) the service returns "not in your
documents" without calling the model at all. An open-book system that invents an answer is worse
than one that admits the gap — and those refusals are recorded, which is what powers the
coverage-gaps screen.

### Chunk metadata is what makes deletion possible

The vector store has no foreign keys. Every chunk carries `tenant_id` and `document_id` metadata,
and deleting a document issues a filtered delete against the store. Without that, deleted
documents would keep answering questions.

### Re-indexing needs the original bytes

`document_content` keeps the uploaded file in its own table — separate from `documents` so listing
never drags blobs into memory. It exists so a chunking or embedding-model change can be rolled
out: re-index drops the old vectors first, otherwise the corpus silently mixes two strategies.

### Embedding dimensions must match

`spring.ai.vectorstore.pgvector.dimensions` **must** equal your embedding model's output —
`nomic-embed-text` is 768. A mismatch fails loudly at insert, which is the good case; the bad case
is a model swap that quietly degrades retrieval.

---

## Caching

Three caches, with different lifetimes because what makes a hit stale differs.

| Cache | Keyed on | Held | Why that long |
|---|---|---|---|
| `embeddings` | text + model | 30 days | embedding is deterministic; a hit can never be stale |
| `insights` | source text + prompt + model | 1 day, and in the database forever | same inputs, same output — and the most expensive thing here to recompute |
| `retrieval` | question + scope | seconds | the only one the corpus invalidates underneath |

Transformation output is cached in two tiers. Redis is fast and forgettable; the insights table
is slower and permanent. Redis alone would re-run every transformation after a restart, and the
table alone would give up the millisecond path that makes repeated requests worth serving.

A cached result is reported as cached, never presented as fresh — otherwise someone editing a
prompt cannot tell whether the output in front of them reflects the edit. Measured on this
corpus: a first run of `summary` took 46.7 s, and identical repeats came back in 2–14 ms.

Editing a prompt changes the key rather than needing an eviction, because the prompt is part of
it. Renaming a transformation does not.

The embedding cache is applied by a bean post-processor rather than a `@Bean` with
`@ConditionalOnBean`. That condition is evaluated while user configuration is parsed, before the
AI auto-configuration has registered the model it asks about, so it never matches and the cache
is silently absent — embeddings simply get recomputed forever. It fails by working, which is why
`CachingEmbeddingModelTests` asserts on the number of calls that reach the model rather than on
the answers.

---

## Sources from a URL

```bash
curl -X POST localhost:8081/api/documents/url \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","namespace":"default"}'
```

The fetch runs as this server, from inside its network, so the address is checked before a socket
is opened and again after every redirect — a public URL that redirects to `169.254.169.254` would
otherwise defeat a check done only on the original. Loopback, private, link-local, unique-local,
carrier-grade NAT and multicast addresses are all refused, and only `http` and `https` are
accepted. The body is read against a hard cap rather than trusting `Content-Length`.

`ossian.fetch.allow-private-addresses` turns the check off for reaching an internal wiki from a
laptop. It is off by default and should stay off anywhere the service is reachable by someone you
would not hand a shell to.

---

## The login page

Keycloak serves a themed login at `keycloak/themes/ossian`, mounted into the container and set
on the realm as `loginTheme`. It extends `keycloak.v2` rather than replacing it: Keycloak's
login covers a long tail of flows — OTP, WebAuthn, recovery codes, consent, expired links — and
a from-scratch theme quietly breaks the ones nobody tests until a user hits them. Inheriting
changes only the appearance.

Realm JSON and themes are mounted from separate directories, because `--import-realm` scans its
directory for realm files and a theme tree living inside it is at best noise.

---

## Namespaces

A namespace partitions one tenant's corpus. The tenant is the security boundary and comes from
the token; a namespace is an organisational one and comes from the request. A user may read
across their own namespaces and can never read another tenant's, whichever namespace they name.

An unknown namespace resolves to the default rather than erroring. The alternative — a typo
silently returning an empty corpus — reads as "my documents are gone" and sends people looking
in the wrong place.

Not every page is namespace-scoped, and the UI says which. Pages the switcher filters carry a
dot in the navigation; on the others the switcher greys itself out and says so. A global control
that silently does nothing on half the app is worse than no control at all.

| Page | Scoped | Why |
|---|---|---|
| Notebook | yes | retrieval is filtered to the namespace |
| Vectors | yes | chunks, search and the projection all narrow |
| Console | yes | corpus and retrieval stats narrow; jobs stay tenant-wide |
| Imports | no | the feed records what a pipeline sent across all namespaces |
| Settings | no | settings are per tenant — one model serves every namespace |
| API | no | you choose the parameters yourself |

**Tenant is not namespace.** The tenant comes from the `tenant` claim in the token and is the
security boundary; it is shown in the sidebar as a label, never as a control. A namespace is
chosen per request and is organisational. In the demo realm `admin` and `user` are in `acme`,
`other` is in `globex`.

---

## Settings

`/api/admin/settings` exposes the model, retrieval and ingestion tunables. `application.yml`
supplies the default; a tenant override is stored per tenant and shadows it, so changing a
retrieval threshold needs neither a redeploy nor agreement from every other tenant in the
process. Clearing a field restores the default rather than setting an empty value.

Chunk size and overlap apply to documents indexed *afterwards*. Existing chunks keep their old
shape until reindexed, which is why those two are flagged in the UI.

---

## Event-driven ingestion

`POST /api/events/documents` is for pipelines rather than people — a CDC stream, a webhook, a
queue consumer:

```bash
curl -X POST http://localhost:8081/api/events/documents \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"eventId":"crm-4172-v1","operation":"UPSERT","externalId":"crm/4172",
       "namespace":"default","source":"crm-cdc","text":"..."}'
```

Three properties follow from that, and they are the design:

- **Idempotent.** `eventId` is caller-assigned and unique per tenant. Redelivery is normal —
  brokers promise at-least-once and pipelines crash mid-batch — so a repeat returns the
  original outcome instead of a second document.
- **Addressed externally.** Documents are keyed by `externalId`, their identity in the source
  system. An update to row 4172 upstream replaces the chunks made from row 4172; the caller is
  not expected to remember our UUID.
- **Batched.** `POST /api/events/documents/batch` takes up to 500 and reports per event. A batch
  is not a transaction: one bad record must not send the other 499 back to be redelivered.

`clients/java` wraps this. It takes a token supplier rather than a token, because an importer
outlives the token it started with — a client that captured one at construction works in testing
and starts failing minutes after deployment.

---

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `ossian.ingest.chunk-size` | `1200` | Characters per chunk |
| `ossian.ingest.chunk-overlap` | `200` | Overlap so facts spanning a boundary stay findable |
| `ossian.ingest.max-file-size` | `25 MB` | Upload limit |
| `ossian.retrieval.top-k` | `6` | Chunks per question |
| `ossian.retrieval.similarity-threshold` | `0.5` | Below this, refuse to answer |
| `ossian.retrieval.cache-seconds` | `300` | Retrieval cache TTL |
| `ossian.chat.system-prompt` | see config | Instruction that enforces citation and refusal |
| `LLM_BASE_URL` | `http://localhost:8090` | OpenAI-compatible endpoint |
| `LLM_EMBED_MODEL` / `LLM_EMBED_DIMENSIONS` | `nomic-embed-text` / `768` | Must agree |

Caches have deliberately different lifetimes: `embeddings` lives 30 days (identical text always
embeds the same, so re-ingesting an unchanged file is free), `retrieval` only 5 minutes (the
corpus changes underneath it).

---

## API

| Method | Path | Role |
|---|---|---|
| `POST` | `/api/chat` | user |
| `POST` | `/api/chat/stream` | user — SSE token stream |
| `GET` `POST` `DELETE` | `/api/documents` | user |
| `GET` | `/api/admin/stats/corpus` | admin |
| `GET` | `/api/admin/stats/retrieval` | admin |
| `GET` | `/api/admin/gaps` | admin — unanswered questions |
| `GET` | `/api/admin/jobs` | admin |
| `POST` | `/api/admin/documents/{id}/reindex` | admin |

OpenAPI at `/swagger-ui.html`, metrics at `/actuator/prometheus`.

---

## Building

```bash
./mvnw verify
```

Integration tests use Testcontainers and **need Docker running** — tenant scoping lives in SQL
predicates, and a mocked repository would prove nothing about them. Model calls are stubbed, so
no LLM is required.

```
backend/        Spring Boot service
frontend/       React 19 + Vite + TypeScript
clients/java/   client library for feeding a corpus from another service
keycloak/       realm import (keycloak/realm) and the login theme (keycloak/themes)
gateway/        route override mounted into the spring-llm-gateway image
docs/           sample corpus to ingest
scripts/        smoke test against a running stack
```

### Smoke test

`./mvnw verify` proves the code. This proves the deployment — it runs against whatever is
actually up, so it catches the wiring that unit tests cannot: a wrong base URL, a model the
gateway has no route for, a realm that did not import.

```bash
./scripts/smoke.sh
```

It signs in to Keycloak, uploads `docs/samples/`, waits for ingestion, asks five questions —
four answerable from the corpus and one deliberately not — then checks tenant isolation and
admin RBAC and prints the console stats. Pass your own files as arguments to use them instead.

The fifth question matters: an answer to it means grounding has broken, because nothing in the
corpus mentions it. Watch for `grounded=False` there and `grounded=True` on the rest.

## License

Apache-2.0
