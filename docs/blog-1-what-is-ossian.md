# The boring 90% of RAG

*A retrieval system is a weekend to demo and a quarter to operate. This is the quarter.*

---

Everyone has built the demo. Embed some documents, stuff the nearest chunks into a prompt, get an
answer with a citation. Two hundred lines, one afternoon, genuinely impressive in a meeting.

Then somebody uses it.

They ask something the corpus does not cover and get a confident paragraph of invention. They
upload a 200-page PDF and it silently becomes one chunk. They ask why an answer was wrong and
nobody can tell whether the retriever found the wrong passage or the model misread the right one —
which matters, because those have opposite fixes. Three months later the embedding bill is four
figures and nobody can say which documents caused it.

None of that is the interesting part of RAG. All of it is the part that decides whether the thing
survives contact with a real company.

**Ossian** is the boring 90%. It is an open-book question-answering service over your own
documents, plus the operational surface that a demo skips: ingestion you can watch, retrieval you
can inspect, credentials a machine can hold, limits that stop one runaway process, and a record of
who did what.

It is Apache-2.0, self-hosted, and runs on one `docker compose up`.

---

## What it actually does

**Answers from your documents, with citations you can open.** The passage that produced the answer
is one click away, verbatim. Not a summary of the source — the source.

**Refuses when it cannot answer.** Below a similarity threshold you get *"I could not find anything
about that in your documents"* and the model is never called. This is the feature people
underestimate. An unsupported answer is worse than no answer, because it is indistinguishable from
a good one. Ask Ossian who won the 1998 World Cup and it declines — the corpus is a company
handbook, and it says so rather than reaching for what the model happens to know.

Every refusal is recorded. The **coverage gaps** screen is the list of questions your documents
could not answer, newest first, which is the most useful ingestion backlog anyone has ever handed
a documentation team.

**Namespaces**, so a question about the runbooks is not answered by the contracts. Chunking is
configurable per namespace, because a runbook answers best in small passages — a question is about
one procedure — while a contract answers worst that way, since a clause cut in half means the
opposite of what it says. Same file, two namespaces, verified:

```
namespace | chunks | avg_chars
----------+--------+----------
default   |      4 |     1082
runbooks  |     15 |      323
```

**Transformations**: named prompts run over a *whole* source rather than retrieved fragments.
Summary, key points, open questions, action items. This is a different operation from asking a
question and the distinction matters — "summarise this" cannot be answered from the three chunks
nearest the word *summarise*, so transformations skip the retriever entirely and read the document.

**Agent memory**, kept deliberately apart from the corpus. Both are embedded text, which makes one
table tempting and wrong: document retrieval filters on namespace, so memories would surface as
citations in ordinary answers. *"According to [1], the user prefers dark mode"* is not a fact from
your corpus and there would be no way to tell. Memory ranks on
`similarity × importance × 0.5^(age / 30 days)` — recency counts for memory in a way it never does
for documents, because a runbook from three years ago is as true as one from today and a stated
preference from three years ago is not.

---

## The parts that only matter in production

**A vector inspector.** Every stored chunk, its dimensions and norm, a 2-D PCA projection of the
corpus, and a retrieval playground that runs a query with no model in the loop. That last one earns
its place on its own: retrieval failures and generation failures look identical from the outside. If
the right passage is at the top here, retrieval is fine and the model is your problem. If it is not,
stop reading prompts.

**Machine credentials.** An agent cannot perform an interactive browser login, so for a while
everything here was unreachable to exactly the callers it is meant to serve. API keys fix that:
random, stored only as a SHA-256 hash, returned once. A key carries its own roles — usually fewer
than the person who issued it, because an ingestion pipeline needs to write documents, not
administer the installation — and can be **confined to a namespace**, so a leaked pipeline key
cannot read the rest of the corpus.

**Rate limits that understand what ingestion costs.** Two limiters, because one number cannot
describe both. The HTTP limiter counts requests. The ingestion limiter counts *embedding tokens*,
because one upload is a single request and can be a million tokens — an ingestion that respects a
request limit perfectly can still burn a day's model budget in a minute. Ingestion **waits** rather
than failing: the caller was already told the document is pending, so slowing down is invisible and
correct, where failing would turn a queue into a pile of errors to retry by hand.

**Embedding batches measured in tokens.** Endpoints reject on total tokens, so a fixed count of
chunks per call is the wrong unit: twenty-five short notes and twenty-five long passages are the
same number and wildly different requests. Counted with a real tokenizer, not the
four-characters-per-token rule — that heuristic is roughly right for English prose and badly wrong
for code, tables, JSON and non-Latin scripts, which means a limit set from it is exceeded exactly on
the documents most likely to be large.

**Observability that answers the question people ask.** Prometheus has the rates. The console has
what a graph cannot: failures grouped by cause (on the first line of the message, since stack traces
make twenty instances of one bad file type look like twenty problems), and documents needing
attention — including anything left processing for over an hour, because nothing marks those failed.
The process that would have is the one that died.

**An append-only audit trail**, recording the actor as presented at the time rather than a foreign
key to a user. People leave and keys get revoked, and `key:nightly-import` still means something
afterwards where a dangling reference does not.

**Runtime settings.** Model, temperature, chunk size, overlap, how many passages and how good a
match has to be — all editable without a redeploy. The two that require a re-index say so, because
chunking changes are not retroactive and silently mixing two strategies in one corpus is worse than
a stale one.

---

## Three things I got wrong, which is the useful part

**The caches were decoration.** `AppConfig` declared an embeddings cache and a retrieval cache with
a comment explaining their lifetimes. `@EnableCaching` was on. There was not a single `@Cacheable`
anywhere. Nothing had ever been cached. The comment described behaviour that did not happen, and
everything worked, so nothing pointed at it.

Fixing it produced a second, worse bug: vectors written as bare JSON arrays into a serializer with
default typing wrote cleanly and failed to read back. Redis filled with keys. Every lookup missed.
Both failures look exactly like a working cache — the answers are identical and only the bill
differs, which is why the tests now count calls that reach the model rather than checking answers.
Five identical queries went from five upstream calls to one.

**Namespace confinement stored and enforced nothing.** I built API keys that could be confined to a
namespace, wrote it in the README, and shipped it. A key scoped to `hr-policies` listed all six
documents and cheerfully answered from another namespace. The column was set; nothing read it. An
advertised boundary that does not exist is worse than no boundary, because someone plans around it.

It is now applied where the namespace is *chosen* rather than at the edge, because reading the
request parameter directly is precisely the bug: a confined credential has a namespace even when the
request names none.

**Chunk size was in the wrong unit.** `chunk-size: 1200` looked like characters. Spring AI's
`TokenTextSplitter` counts tokens, so a 3,728-character document sailed under the limit and became
one chunk. And `chunk-overlap: 200` was being passed to `withMinChunkSizeChars`, which is not
overlap at all — the splitter has no overlap support, so the setting did nothing. Overlap is not
cosmetic: without it a fact straddling a boundary is in neither chunk well enough to retrieve, and
the symptom is a document that visibly contains the answer while the retriever never returns it.

The pattern in all three: **the system worked the whole time.** Nothing threw. That is what the
operational surface is for.

---

## Who this is for

**A company that wants RAG over its own documents and needs to run it itself.** Regulated industries,
anything under a data-residency rule, anyone whose documents cannot go to a third party. One
deployment serves one organisation — there is no tenant column, because the boundary is the
deployment.

**A team building agents that need somewhere to read from and somewhere to remember.** The API is
the product as much as the console is: keys, memory, event-driven ingestion for CDC streams and
webhooks (idempotent on a caller-assigned event id, because at-least-once delivery is the normal
case), and an OpenAPI description.

**Anyone who has built the demo and hit the second month.** If you recognise the failures at the top
of this post, most of what is here is the list of things you were about to write.

### Who it is not for

**You want a polished consumer product.** Gemini Notebook is free, excellent, and generates a
podcast about your sources. Ossian does not, and will not.

**You want the widest model support.** Ossian speaks the OpenAI protocol to a gateway. That covers a
great deal, but Open Notebook supports 18+ providers directly and is the better answer if provider
breadth is what you need.

**You want maturity.** This is young. It is well tested — 89 tests, and the integration ones run
against a real Postgres with pgvector because the scoping lives in SQL predicates and a mocked
repository would prove nothing — but it has not been run in anger by anyone but me.

---

## The stack, and one deliberate choice

Spring Boot 3.5 on Java 21, Spring AI, Postgres 17 with pgvector (HNSW, cosine), Redis, Keycloak for
OAuth 2.0, React 19 with Vite. Flyway owns the schema; Hibernate never touches it.

The one choice worth explaining: **Ossian holds no model credentials.** Every call goes through
[spring-llm-gateway](https://github.com/dockndevai/spring-llm-gateway), which owns virtual keys,
per-key token quotas, usage metering and failover. Swapping a model — or a whole provider — is a
routing change there rather than a deployment here. It also means the metering is somebody else's
problem, which is the correct number of places for that to live.

```bash
git clone https://github.com/dockndevai/ossian && cd ossian
docker compose up -d
docker compose exec ollama ollama pull nomic-embed-text
docker compose exec ollama ollama pull qwen2.5:3b
./mvnw -pl backend spring-boot:run
cd frontend && npm install && npm run dev
```

Then `./scripts/smoke.sh`, which signs in, uploads a sample corpus, asks five questions — four
answerable and one deliberately not — issues a namespace-confined key and checks it is refused
elsewhere, and prints the console stats. The fifth question is the one that matters: an answer to it
means grounding has broken.

---

*Ossian is Apache-2.0 at [github.com/dockndevai/ossian](https://github.com/dockndevai/ossian).
Issues and disagreement welcome — particularly about the refusal threshold, which is the setting
most likely to be wrong for your corpus.*
