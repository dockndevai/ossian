# Reddit — blog 3

Best fits, in order: **r/Rag**, **r/LocalLLaMA**, **r/dataengineering** (Kafka variant).

One subreddit at a time, a few days apart. Read each self-promotion rule first. Text post, links in
the body, substance up front — these audiences reward what went wrong, not a feature list.

---

## r/Rag · r/LocalLLaMA

**Title**

```
A question about conflicting context found two bugs in my self-hosted RAG — both passed their tests
```

**Body**

Someone asked how my RAG system handles retrieved documents conflicting with an agent's memory, and
context that comes from the same underlying source. I had a confident answer. Reading the code to
check it, half of it was wrong.

**1. Three chunks of one document looked like three sources.** Retrieval numbered context per chunk,
so the model saw `[1] handbook [2] handbook [3] handbook [4] architecture` — three sources agreeing,
one dissenting. It's one source said three times, and the "say which sources disagree" instruction
had no way to tell. Content-hash dedup at ingest doesn't help: they're distinct chunks of one
legitimate document. Fix: group by document id before building the prompt, one number per document,
every passage kept.

**2. Memory decayed from the wrong moment.** Ranking is `similarity × importance × 0.5^(age/30d)`,
but age came from `created_at`, so restating a fact never refreshed it. The obvious fix — age from
`last_used_at` — is wrong: recall returns the old preference *and* the newer one contradicting it,
so refreshing on read ties them on recency, the only signal that lets the newer one win. Age now
runs from the last time something was *said*. Live: stale 0.102, fresh 0.765, restated 0.817.

The recency test passed throughout — it backdated `created_at`, the same column the bug read.

**3. Found while building the next thing.** A Debezium → Kafka Connect sink's first run showed a
batch failing with 500 twice, then succeeding. A DELETE event recorded the id of the document it had
just deleted → FK violation → whole batch fails. The retry found nothing to delete and passed, so
every retrying pipeline hid it.

Still open: memories don't link back to the document they came from, and a contradicting memory
doesn't supersede the old one. Curious how others handle provenance between agent memory and the
corpus.

Write-up: <canonical URL>
Code (Apache-2.0): https://github.com/dockndevai/ossian

---

## r/dataengineering (Kafka / CDC angle)

**Title**

```
Debezium → Kafka → RAG corpus: a Kafka Connect sink, and why the event id can't be Debezium's LSN
```

**Body**

I wrote a Kafka Connect sink that turns Debezium change events into documents in a self-hosted RAG
service, so a knowledge-base table becomes a corpus that follows the database. A few decisions that
were easy to get wrong:

- **Idempotency key from Kafka, not Debezium.** Every row in an initial snapshot shares one LSN, so a
  source-position id collapses different rows and the second is dropped as a duplicate. The sink uses
  connector name + topic-partition-offset + record timestamp (for topics recreated from offset 0).
- **Blanked text → delete, missing columns → reject.** Emptying every text column removes the
  document, or the corpus keeps answering from text the source no longer has. But if none of the
  configured columns exist, it's a misconfiguration — otherwise one typo deletes the table from the
  corpus.
- **`__debezium_unavailable_value` is refused.** With default replica identity, a TOASTed column
  untouched by an update arrives as a placeholder; indexing it replaces an article with a sentinel.
- **Offsets never ahead of delivery.** Synchronous `put()`, `Retry-After`-aware backoff, DLQ for
  rejected records, and a 401 stops the task so a bad key can't drain the topic into the DLQ.

Tested end to end: snapshot, update, delete, blanked row, and an offset reset replaying the whole
topic — 18 events before and after, no new documents. The first run also caught a backend bug where
DELETE events 500'd once per document, hidden by retries.

Sink: https://github.com/dockndevai/ossian-kafka-connect
