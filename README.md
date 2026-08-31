# Openbook

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
│  openbook    │          │ spring-llm-gateway │   usage metering, failover
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
docker compose exec ollama ollama pull qwen2.5:0.5b
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

---

## The parts worth knowing

### Tenancy is a token claim, never a parameter

`TenantContext` reads the `tenant` claim from the validated JWT. Every query, every retrieval
filter and every stat is scoped by it. There is deliberately no `findById(id)` exposed on the
document repository — knowing an id must not be enough to read another tenant's document, so
lookups are always `findByIdAndTenantId`, and a foreign id returns **404 rather than 403** so the
response doesn't even confirm the document exists.

### Retrieval refuses rather than guesses

Below `openbook.retrieval.similarity-threshold` (default 0.5) the service returns "not in your
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

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `openbook.ingest.chunk-size` | `1200` | Characters per chunk |
| `openbook.ingest.chunk-overlap` | `200` | Overlap so facts spanning a boundary stay findable |
| `openbook.ingest.max-file-size` | `25 MB` | Upload limit |
| `openbook.retrieval.top-k` | `6` | Chunks per question |
| `openbook.retrieval.similarity-threshold` | `0.5` | Below this, refuse to answer |
| `openbook.retrieval.cache-seconds` | `300` | Retrieval cache TTL |
| `openbook.chat.system-prompt` | see config | Instruction that enforces citation and refusal |
| `LLM_BASE_URL` | `http://localhost:8080` | OpenAI-compatible endpoint |
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
backend/    Spring Boot service
frontend/   React 19 + Vite + TypeScript
keycloak/   realm import — users, roles, tenant claim mapper
```

## License

Apache-2.0
