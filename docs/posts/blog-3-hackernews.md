# Hacker News — blog 3

Submit as a **link** post to the write-up's canonical URL, then add the comment below as the first
comment. No marketing register.

**Title**

```
A question on Discord found two bugs in my RAG system; fixing them found a third
```

**First comment**

Author here. Ossian is a self-hosted RAG backend (Apache-2.0, Spring Boot + pgvector) aimed at being
queried by other software rather than read by a person.

Someone asked how it handles retrieved documents conflicting with agent memory, and context that
derives from one source. Checking my answer against the code turned up:

- context numbered per chunk, so three passages of one document read as three agreeing sources;
- memory decay measured from creation, so restating a fact never refreshed it — and the tempting fix
  (refresh on recall) would tie a stale preference with the newer one contradicting it;
- and, while building a Debezium/Kafka Connect sink, a DELETE event that 500'd exactly once per
  document because it recorded a foreign key to the row it had just deleted. Retries hid it.

The common thread is that each was surrounded by something agreeing with it: a test backdating the
same column the query read, a citation format that looked fine in screenshots, a retry loop turning
a deterministic failure into a transient one.

The part I haven't solved is provenance between memory and the corpus — a memory learned from a
document doesn't know when that document changes. Interested in how others approach it.
