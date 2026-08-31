# Gemini Notebook, Open Notebook, Ossian: three tools that look alike and aren't

*They all answer questions about documents you give them. That is where the similarity ends.*

---

Every few months someone asks me which "NotebookLM alternative" to use, and the question is
malformed. The three tools people mean are not competing for the same job. Picking between them on
features is how teams end up six months into the wrong one.

Here is what each is actually for, from someone who has used the first two and wrote the third.

**A note on names:** Google renamed NotebookLM to **Gemini Notebook** on 16 July 2026. Same product,
same notebooks, nothing to migrate. I use the new name throughout; most of the internet still says
NotebookLM.

---

## The one-line version

| | Built for |
|---|---|
| **Gemini Notebook** | a person, reading sources, who wants excellent answers with zero setup |
| **Open Notebook** | a person who wants that, privately, on their own machine, with any model |
| **Ossian** | a company that needs a documented corpus other software can query |

The first two are notebooks. The third is infrastructure that happens to have a notebook on top.

---

## Gemini Notebook

Google's, free, and very good. Upload sources, ask questions, get answers grounded in them with
inline citations. The Audio Overview — two synthetic hosts discussing your documents — is the
feature that made it famous and is genuinely delightful. Since July it also has a sandboxed cloud
computer that writes and executes code against your sources.

**What it is best at:** being finished. There is no deployment, no model choice, no chunk size. It
works on a phone. For one person reading twenty papers, nothing else is close.

**The limits are real, though.** Sources per notebook are capped by plan — 50 on free, more on paid
tiers — and every source tops out at 500,000 words or 200MB whatever you pay. The free tier caps
daily chat questions. These are fine for a person and immediately disqualifying for a corpus.

**On data:** Google states your data is not used to train the model unless you submit feedback, and
that Workspace and Education uploads are never human-reviewed and never used for training. That is a
clear commitment and better than most. It is still someone else's computer, which for a great many
organisations ends the conversation before any feature comparison starts.

**There is no API.** This is the deciding fact and it is easy to skim past. You cannot point an
agent at it, cannot ingest from a pipeline, cannot query it from your own application. It is a
destination, not a component.

---

## Open Notebook

An MIT-licensed, self-hosted alternative by Luis Novo — Python and FastAPI, a Next.js front end,
SurrealDB for both relational and vector storage. `docker compose up` and you have a private
notebook on your own box.

**What it is best at: choice and privacy, without giving up the notebook experience.** 18+ model
providers out of the box — OpenAI, Anthropic, Google, Mistral, Groq, DeepSeek, Ollama, LM Studio,
OpenRouter — so you can run entirely local and your sources never leave the machine. Its podcast
generation goes further than Google's: 1–4 speakers with configurable Episode Profiles, against
Gemini Notebook's fixed two-host format.

It also has **transformations** — customisable prompts that summarise or extract insights from a
whole source. I liked the idea enough to build it into Ossian, and I am saying so plainly because
the concept is theirs.

There is a full REST API, and optional password protection for public deployments.

**Where it stops:** it is designed around a person using it. Optional password protection is the
right amount of auth for a private deployment and is not role-based access control. There is no
per-credential rate limiting, no audit trail, no admin console for watching ingestion fail. That is
not a criticism — it is a different product, and adding those would make it worse at what it is
for.

---

## Ossian

Mine. Apache-2.0, Spring Boot and Spring AI, Postgres with pgvector, Redis, Keycloak, React.

**What it is built for: being queried by other software, inside a company, and operated by someone
who is on call for it.** The notebook UI exists so a person can use the corpus, but it is not the
point. The point is everything around it:

- **Keycloak OAuth 2.0** with roles, and **API keys** so an agent can authenticate without a
  browser — a key carries its own roles and can be confined to a single namespace
- **Two rate limiters** — one counting HTTP requests, one counting *embedding tokens*, because one
  upload is a single request and can be a million tokens
- **An append-only audit trail** of who did what, recording the actor as presented at the time
- **A vector inspector** — every stored chunk, a 2-D projection of the corpus, and a retrieval
  playground that runs a query with no model in the loop, so you can tell a retrieval failure from
  a generation failure
- **Ingestion observability** — Prometheus metrics, failures grouped by cause, and a retry button
- **Agent memory**, ranked on recency and importance, deliberately in a separate store from the
  corpus so it never surfaces as a citation
- **Event-driven ingestion** for CDC streams and webhooks, idempotent on a caller-assigned event id
- **Per-namespace chunking**, because runbooks and contracts want different chunk sizes

**Where it stops:** it is the youngest by a wide margin and has been run in anger by nobody. It
speaks the OpenAI protocol to a gateway rather than integrating providers directly, so Open
Notebook's provider list is longer. It does not make podcasts and never will. And it asks more of
you — Postgres, Redis, Keycloak and a model gateway is a real deployment, not a weekend.

---

## Side by side

| | Gemini Notebook | Open Notebook | Ossian |
|---|---|---|---|
| Licence | proprietary | MIT | Apache-2.0 |
| Runs on | Google's servers | yours | yours |
| Setup | none | `docker compose up` | compose + Keycloak + a gateway |
| Source limits | 50–600 per notebook by plan | your disk | your disk |
| Models | Gemini | 18+ providers, or fully local | whatever the gateway routes to |
| Auth | Google account | optional password | OAuth 2.0 + roles + API keys |
| API | none | full REST | full REST + OpenAPI |
| Audio overview | yes | yes, 1–4 speakers | no |
| Transformations | — | yes | yes |
| Vector inspection | no | no | yes |
| Rate limiting | by plan quota | no | per-credential, requests and tokens |
| Audit trail | no | no | yes |
| Agent memory | no | no | yes |
| Ingestion metrics | no | no | Prometheus + console |
| Maturity | shipped, huge | mature, active | new |

Read that table carefully in both directions. The bottom half is why you would choose Ossian; the
top half is why you probably should not.

---

## How to choose, honestly

**Pick Gemini Notebook** if you are one person, your sources fit its limits, and your documents can
live on Google's infrastructure. It is free and better than what you would build. Most people
asking this question should stop here.

**Pick Open Notebook** if you want that experience but the documents cannot leave your machine, or
you want to choose the model, or you want better podcasts. It is the right answer for privacy-
conscious individuals and small teams, and it is mature in a way Ossian is not.

**Pick Ossian** if — and only if — you can answer yes to most of these:

- Other software needs to query the corpus, not just people
- You need to know who read what, and prove it later
- One runaway process must not exhaust your model budget
- You need to see *why* an answer was wrong, at the retrieval layer
- Someone will be on call for it

If that list reads as a description of your week, the notebooks will not hold you. If it reads as
someone else's problem, they will, and you should use one.

---

## The thing all three share

All three refuse to answer from outside their sources. Gemini Notebook grounds strictly in what you
upload. Open Notebook does the same. Ossian returns *"I could not find anything about that in your
documents"* below a similarity threshold and never calls the model.

That constraint is the whole category. A general assistant that also read your PDFs is a different
and much less useful thing, because you cannot tell which half of an answer came from where. The
value is not that these tools answer — it is that they decline.

Whichever you pick, test that first. Ask it something your corpus definitively does not cover. If
you get a confident paragraph back, nothing else about the tool matters.

---

*Ossian: [github.com/dockndevai/ossian](https://github.com/dockndevai/ossian) ·
Open Notebook: [github.com/lfnovo/open-notebook](https://github.com/lfnovo/open-notebook) ·
Gemini Notebook: [notebooklm.google.com](https://notebooklm.google.com)*

*I wrote one of these three, which you should weigh accordingly. Corrections about the other two are
welcome — the Open Notebook and Gemini Notebook facts here were checked against their own
documentation in August 2026, and both move quickly.*
