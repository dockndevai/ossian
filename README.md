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
token quotas and metering. Point `LLM_BASE_URL` straight at Ollama to cut it out.

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

| user | password | roles |
|---|---|---|
| `admin` | `admin` | user + admin |
| `user` | `user` | user |
| `other` | `other` | user + admin |

Sign in as `user` to see the console disappear: the admin surface is gated on the
`ossian-admin` realm role, and the backend enforces that independently of the navigation.

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

### One installation serves one organisation

There is no tenant column and no tenant claim. The boundary is the deployment: a company runs its
own Ossian over its own documents, and namespaces partition that corpus so a question can be asked
of the handbooks without the runbooks answering.

`CallerContext` answers who is making a request, resolving a Keycloak token or an API key to the
same shape so nothing downstream cares which arrived. The isolation that does exist between
callers is on credentials rather than people: a key can be confined to one namespace, and a
missing document returns **404 rather than 403** so the response does not confirm what exists.

### Retrieval refuses rather than guesses

Below `ossian.retrieval.similarity-threshold` (default 0.5) the service returns "not in your
documents" without calling the model at all. An open-book system that invents an answer is worse
than one that admits the gap — and those refusals are recorded, which is what powers the
coverage-gaps screen.

### Chunk metadata is what makes deletion possible

The vector store has no foreign keys. Every chunk carries `document_id` and `namespace` metadata,
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

## Rate limits

A token bucket per caller, in Redis so the limit holds across instances — two replicas each
enforcing sixty a minute enforce a hundred and twenty. The refill, the read and the spend happen
inside one Lua evaluation so two requests cannot interleave.

A bucket rather than a fixed window, because a window is wrong at its edges: a caller can spend a
full allowance in the last instant of one and again in the first instant of the next, so 60/minute
permits 120 in two seconds. A bucket refills continuously and has no edge — while still allowing
the bursts that are normal, since a page loading six panels makes six requests.

| Caller | Default | Why |
|---|---|---|
| API key | 120/min | a process in a retry loop is the realistic way this falls over |
| Person | 600/min | a console page fans out, and a person cannot loop |

Per-key with `requestsPerMinute` at issue time: a bulk importer legitimately makes thousands an
hour and an interactive agent makes dozens, and one limit suiting both suits neither. Refusals
carry `Retry-After`, because a client told only "no" retries immediately and makes it worse.

**It fails open.** If Redis is unreachable requests are allowed, with a warning. A limiter that
fails closed turns a cache outage into a total outage, and one caller making too many requests is
survivable in a way that refusing every request is not. The warning matters — a limiter that has
silently stopped limiting looks exactly like one with nothing to do.

---

## Audit

Append-only, and readable in the console. Every entry records the actor **as presented at the
time** rather than a foreign key to a user: people leave and keys get revoked, and
`key:nightly-import` still means something afterwards where a dangling reference does not.

Recorded: document uploaded and deleted, key issued and revoked, setting changed, memory written
and forgotten — with the outcome, so a rejected change is in the trail alongside an accepted one.

Two things are deliberately *not* recorded. Request bodies, because an audit row carrying them
becomes a second copy of the data it is meant to be watching. And memory content — the subject and
kind are enough to answer "what did this agent learn about this person" without restating it.

Writes use `REQUIRES_NEW`, so a row survives the rollback of the thing it describes: an attempt
that failed halfway is more interesting than one that succeeded, and joining the audit to the
caller's transaction would discard exactly those. A failed audit write never fails the request —
it is logged at ERROR instead, because a gap you know about is worth far more than one you do not.

---

## Ingestion observability

Prometheus has the rates; the **Console** answers the question people actually ask, which is why
one document is stuck.

```bash
# a scraper authenticates with an API key, like any other machine caller
curl -H "X-API-Key: osk_..." localhost:8081/actuator/prometheus | grep ^ossian_
```

| Metric | Tags | Reads as |
|---|---|---|
| `ossian_ingest_duration_seconds` | namespace, outcome | how long a document takes, and whether it made it |
| `ossian_ingest_chunks_total` | namespace | what the embedding bill is charged in |
| `ossian_retrieval_duration_seconds` | namespace, grounded | question latency, split by whether the corpus could answer |
| `ossian_retrieval_chunks_total` | namespace | passages returned per question |
| `ossian_transform_duration_seconds` | transformation, cached | what the cache is saving |

The `grounded` tag is the corpus health signal: a rising rate of `grounded="false"` means people
are asking things the documents do not cover, which is a gap to fill rather than a bug to fix.

Tag cardinality is deliberately low — namespace is a tag because a corpus has a handful of them,
document id is not, because a tag with unbounded values turns a time-series database into a very
slow log.

The console adds what a graph cannot: **failures grouped by cause** (on the first line of the
message, since stack traces and ids make every failure look unique when twenty of them are really
one bad file type), and **documents needing attention** — failed, plus anything left processing
for over an hour, because nothing marks those failed; the process that would have is the one that
died.

Retry is a button, not a timer. Most ingestion failures are deterministic — an unreadable file, a
document past the model's context — and retrying those on a schedule spends the embedding budget
rediscovering the same fact. It is worth doing after something was fixed, and only a person knows
when that happened.

---

## Agent memory

An agent's own recollection, separate from the document corpus and separate on purpose. They are
both embedded text, which makes one table tempting and wrong: document retrieval filters on
namespace, so memories would come back as citations in ordinary answers — "according to [1] the
user prefers dark mode" is not a fact from your corpus and there would be no way to tell.

```bash
curl -X POST localhost:8081/api/memory -H "X-API-Key: osk_..." \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"support","subject":"user:ankit","kind":"preference",
       "content":"Ankit prefers British English and no bullet lists.","importance":2.0}'

curl -X POST localhost:8081/api/memory/recall -H "X-API-Key: osk_..." \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"support","query":"how should I write to Ankit?","subject":"user:ankit"}'
```

Three things make it memory rather than a second corpus:

**Recency counts.** For a document the best match is the best answer — a runbook from three years
ago is as true as one from today. For memory the opposite holds, so ranking is
`similarity × importance × 0.5^(age / 30 days)`. A half-life rather than a cliff: durable facts
survive, a stale preference loses to a fresh one.

**Restatement is not new information.** An agent writing what it already knows on every turn
would bury itself, so identical content in the same scope updates the existing row.

**Some of it should be forgotten.** `ttlSeconds` expires session scratch, and expiry is enforced
on read as well as by cleanup — a memory that outlives its stated lifetime is worse than one
never kept.

Scoped by `agentId`, narrowed by `sessionId` or `subject`. Two agents do not read each other's
recollections. The **Memory** page in the console lists what each agent holds and runs the real
ranking, so you can see what an agent *would* recall rather than searching the text on screen —
an agent behaving oddly is usually an agent recalling something stale.

---

## Kubernetes

```bash
kubectl create secret generic ossian-postgres --from-literal=password=…
kubectl create secret generic ossian-gateway  --from-literal=api-key=…
helm install ossian deploy/ossian --set ingress.host=ossian.example.com --set ingress.enabled=true
```

The chart ships **the application only**. Postgres, Redis, Keycloak and the model gateway are
referenced, not deployed — a stateful database packaged inside an application chart is deleted by
`helm uninstall`, and nobody means that.

Four decisions worth knowing, because each is a failure that only shows up in production:

**Liveness does not check the database.** It checks that the process answers. Pointing it at a
health group including Postgres turns a brief database blip into every pod being killed at once,
which is the outage the probe exists to prevent. Readiness *does* include dependencies: a pod that
cannot reach Postgres should stop taking traffic without being restarted.

**Autoscaling is off, and CPU is the wrong signal.** Ossian is bound by the embedding endpoint,
not local CPU — under load the pods sit waiting on the gateway at low utilisation, so a CPU-target
HPA scales *down* exactly when the queue is deepest.

**The grace period is 120 seconds.** Ingestion is asynchronous and a batch takes minutes; a
shorter one leaves half-embedded documents stuck in `PROCESSING` after every rollout, with nothing
left running to mark them failed.

**The frontend resolves its upstream at request time.** nginx resolves a literal `proxy_pass`
hostname once, at startup, and refuses to start if it fails — so a frontend pod scheduled before
the backend Service exists would crash-loop rather than wait. Verified: with a deliberately
unresolvable upstream the pod starts and serves the app, and only the API call 502s.

Health probes are enabled explicitly rather than relying on Boot's Kubernetes auto-detection.
Where that detection does not fire the probe endpoints 404 — and a 404 on liveness is a pod that
restarts forever.

---

## MCP server

Agents reach Ossian over MCP: [`mcp/`](mcp) publishes as
[`ossian-mcp`](https://www.npmjs.com/package/ossian-mcp).

```json
{ "mcpServers": { "ossian": { "command": "ossian-mcp",
  "env": { "OSSIAN_URL": "http://localhost:8081", "OSSIAN_API_KEY": "osk_…" } } } }
```

Seven tools: `ask_documents`, `list_namespaces`, `list_documents`, `add_document_from_url`,
`remember`, `recall`, `forget_session`.

The tool descriptions are unusually explicit about when *not* to use each one. A tool description
is a prompt — it is the only thing the model reads before choosing — and the failure that matters
is not a malformed call but a well-formed call to the wrong tool. `ask_documents` returns a
refusal when the corpus cannot answer, and tells the model to report that rather than falling back
on general knowledge, because an invented answer presented as company policy is the failure this
whole system exists to prevent.

---

## Machine credentials

An agent cannot perform an interactive login, so everything here was unreachable to one. API
keys close that.

```bash
# issue one (admin)
curl -X POST localhost:8081/api/admin/api-keys \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"support-agent","roles":["ossian-user"],"namespace":"hr-policies"}'

# then, with no human anywhere
curl -X POST localhost:8081/api/chat \
  -H "X-API-Key: osk_..." -H 'Content-Type: application/json' \
  -d '{"question":"what do new joiners get on day one?"}'
```

Accepted in `X-API-Key` or as `Authorization: Bearer`. Only the SHA-256 hash is stored and the
secret is returned once — there is no endpoint that reveals an existing key, because a system
where an administrator can read out the installation's credentials is one where whoever reaches
the administrator can too.

A key carries its own roles, usually fewer than the person who issued it: an ingestion pipeline
needs to write documents, not administer the installation. It can also be **confined to a namespace**,
so a leaked pipeline key cannot read the rest of the corpus. Confinement is enforced where the
namespace is chosen, not at the edge — listing, retrieval, the vector endpoints and the console
statistics all ask `NamespaceService` for the effective filter rather than reading the request
parameter, because for a confined credential there is no such thing as "no filter". Naming a
different namespace is a 403 rather than a silent redirect: a pipeline writing to the wrong place
and being told nothing looks like it worked.

| | user token | API key |
|---|---|---|
| roles from | `realm_access.roles` | the key's own roles |
| namespace | any | all, or exactly one |
| revocable | via Keycloak | immediately, and the row is kept |

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

A namespace partitions the corpus. It is an organisational boundary rather than a security one:
a person may read across every namespace, and selecting none searches all of them. The security
boundary is the deployment, plus whatever a given credential is confined to.

An unknown namespace resolves to the default rather than erroring. The alternative — a typo
silently returning an empty corpus — reads as "my documents are gone" and sends people looking
in the wrong place.

Not every page is namespace-scoped, and the UI says which. The picker sits **inside the Work
section** rather than above the navigation: it governs three pages and not the rest, and a
control at the top of a sidebar reads as applying to everything under it. Pages it does affect
carry a dot; on the others it disables itself and says so once — nothing is said in the common
case, because an explanation shown permanently stops being read.

Each option carries a document count (`default · 4`, `runbooks · 0`), since the question people
bring to a namespace picker is which one their documents are in. When looking across all
namespaces every source is labelled with its own, which is the only thing distinguishing two
files that share a name.

| Page | Scoped | Why |
|---|---|---|
| Notebook | yes | retrieval is filtered to the namespace |
| Vectors | yes | chunks, search and the projection all narrow |
| Console | yes | corpus and retrieval stats narrow; jobs stay across all namespaces |
| Imports | no | the feed records what a pipeline sent across all namespaces |
| Settings | no | settings are installation-wide — one model serves every namespace |
| API | no | you choose the parameters yourself |

**One installation, one organisation.** There is no tenant column and no tenant claim: the
boundary is the deployment. Namespaces partition the corpus so a question can be asked of the
handbooks without the runbooks answering — they organise, they do not wall off. The isolation
that does exist between callers is on credentials: an API key can be confined to one namespace,
a person cannot.

---

## Settings

`/api/admin/settings` exposes the model, retrieval and ingestion tunables. `application.yml`
supplies the default; a stored override shadows it, so changing a retrieval threshold needs no
redeploy. Clearing a field restores the default rather than storing an empty value.

Chunk size and overlap apply to documents indexed *afterwards*. Existing chunks keep their old
shape until reindexed, which is why those two are flagged in the UI.

### Chunking per namespace

One chunk size for a whole installation is a compromise between documents that have nothing in
common. A runbook answers best in small passages, because a question is about one procedure; a
contract answers worst that way, because a clause cut in half means the opposite of what it says.

```bash
curl -X PUT localhost:8081/api/namespaces/runbooks/chunking \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"chunkSize":400,"chunkOverlap":80}'
```

Null in either field inherits the installation default, so an existing namespace keeps behaving
exactly as it did and nothing is re-indexed on upgrade. The same file ingested into two
namespaces: 4 chunks averaging 1082 characters in `default`, 15 averaging 323 in `runbooks`.

---

## Ingestion throughput

Three separate bounds, because they fail in three different ways.

**Batching is on tokens, not on a count of chunks.** A fixed item count is the wrong unit —
embedding endpoints reject on total tokens, so twenty-five short notes and twenty-five long
passages are the same number and wildly different requests. One wastes most of the budget it
could have used; the other is rejected outright.

Tokens are counted with the real tokenizer rather than the usual four-characters-per-token rule.
That heuristic is roughly right for English prose and badly wrong for what people actually
ingest: code, tables, JSON and non-Latin scripts all run denser, so a limit set from it is
exceeded exactly on the documents most likely to be large.

**Ingestion has its own rate limit, measured in embedding tokens.** The HTTP limiter counts
requests, which does not describe this at all: one upload is one request and can be a million
tokens of embedding. Ingestion that respects a request limit perfectly can still exhaust a day's
model budget in a minute. It **waits** rather than refusing — the caller has already been told
the document is pending, so slowing down is invisible and correct, where failing the job would
turn a queue into a pile of errors to retry by hand. The wait is bounded, because a job blocked
forever holds a thread and a connection.

**The pool is bounded.** Without it `@Async` uses Boot's default: eight threads and an
effectively unbounded queue, so two hundred uploads queue two hundred embeddings and the failure
surfaces as connection-pool exhaustion, a long way from the cause. Three at a time by default —
ingestion is bound by the embedding endpoint, not by local CPU, and running thirty at once just
spreads the same throughput over thirty half-finished documents instead of finishing three. A
full queue runs the work on the calling thread, so the upload blocks: backpressure the client can
feel.

| Setting | Default |
|---|---|
| `ossian.ingest.embedding-batch-tokens` | 8000 |
| `ossian.ingest.embedding-batch-size` | 25 |
| `ossian.ingest.embedding-tokens-per-minute` | 200000 |
| `ossian.ingest.concurrency` | 3 |
| `ossian.ingest.max-throttle-wait-seconds` | 300 |

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

- **Idempotent.** `eventId` is caller-assigned and unique installation-wide. Redelivery is normal —
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

Integration tests use Testcontainers and **need Docker running** — access control and namespace
scoping live in SQL predicates, and a mocked repository would prove nothing about them. Model
calls are stubbed, so no LLM is required.

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
four answerable from the corpus and one deliberately not — then issues a namespace-confined API
key and checks it is refused elsewhere, checks admin RBAC, and prints the console stats. Pass your own files as arguments to use them instead.

The fifth question matters: an answer to it means grounding has broken, because nothing in the
corpus mentions it. Watch for `grounded=False` there and `grounded=True` on the rest.

## License

Apache-2.0
