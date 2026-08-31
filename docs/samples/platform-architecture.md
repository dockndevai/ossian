# Acme Platform Architecture Notes

## Service topology

The platform is six services behind a single gateway. The gateway terminates TLS, validates the
access token, and routes on path prefix. Nothing behind the gateway is reachable from outside the
cluster, and no service trusts a header it did not set itself.

The order service owns the order lifecycle and is the only writer to the orders database. The
catalog service owns products and pricing. The billing service owns invoices and talks to the
payment provider. The notification service owns email and SMS delivery. The search service owns
the index. The identity service owns users, sessions, and tokens.

Cross-service reads go through published APIs, never through another service's database. This is
enforced by separate database credentials per service rather than by convention, because
convention does not survive an incident at 3am.

## Data storage

Every service has its own Postgres schema with its own role. Shared reference data is replicated
by event rather than read across a boundary, so a slow catalog cannot make checkout slow.

Redis holds sessions, rate-limit counters, and the retrieval cache. It is treated as a cache in
the strict sense: losing all of Redis must degrade latency and nothing else. Any state that
cannot be recomputed goes in Postgres.

Object storage holds uploaded files and generated exports. Files are addressed by content hash,
so re-uploading an identical file is free and deduplication is automatic.

## Event flow

Services publish domain events to Kafka. Events are facts about what happened, named in the past
tense, and never commands. A consumer that cannot handle an event retries with backoff and then
lands the event in a dead-letter topic with the original payload intact.

Ordering is guaranteed per partition key only. Anything needing global ordering is wrong and
should be redesigned around an aggregate that owns the sequence.

Consumers must be idempotent. At-least-once delivery is the contract, and a consumer that breaks
on a duplicate is a bug in the consumer, not in the broker.

## Deployment

Each service ships as a container built from a distroless base. Images are tagged with the git
sha, never with a moving tag, so a rollback is a redeploy of a known sha rather than a rebuild.

Migrations run as a separate job before the new version starts, and must be backwards compatible
with the version currently running. This is what makes a rolling deploy safe: for one window both
versions are live against the same schema.

Feature flags gate anything user-visible. A flag is removed within two sprints of reaching full
rollout; flags that outlive their rollout become permanent untested branches.

## Observability

Every request carries a trace id from the gateway inward, propagated in the standard header.
Logs are structured JSON and include the trace id, the tenant, and the service name.

Alerts fire on symptoms, not causes. High CPU is not an alert. Checkout latency above the SLO is
an alert. The distinction matters because causes change and symptoms are what users feel.

Dashboards are owned by the team that owns the service. An unowned dashboard is deleted after a
quarter of nobody looking at it.

## Security posture

Tokens are short-lived and refreshed. A leaked access token is useful for minutes, not days.

Secrets come from the secret manager at boot, never from the image or the repository. A secret
that appears in a commit is treated as compromised and rotated, even if the commit was reverted.

Dependencies are scanned on every build and the build fails on a known critical vulnerability.
The failure is not overridable in CI; it needs a human to update or explicitly waive.
