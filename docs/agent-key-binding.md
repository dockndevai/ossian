# Binding API keys to agents

Operator guide. For the model in one paragraph, see **Agent memory** and **Machine credentials** in
the [README](../README.md).

## Why

`/api/memory` is keyed by an `agentId` that the **caller supplies**. Until keys could be bound,
the only check on those routes was "is this a valid credential", so any valid API key could list,
recall, overwrite and erase every agent's memory simply by naming it, and could list which agents
exist. `agentId` is a label, not a credential.

A key **bound** to an agent removes that choice: on the memory routes the key is that agent and
cannot be anything else.

## What a bound key can and cannot do

For a key bound to agent `X`, on every `/api/memory` route:

| Request | Result |
|---|---|
| `agentId` omitted (or blank) | acts as `X` |
| `agentId` = `X` | acts as `X` |
| `agentId` = anything else | **403**, nothing is read or written |
| `GET /api/memory/agents` | lists `X` only (empty if `X` holds nothing yet) |
| `DELETE /api/memory/{id}` of a memory belonging to `X` | 204 |
| `DELETE /api/memory/{id}` of anyone else's memory | **404**, identical to an id that never existed |

The same holds for writes (`POST /api/memory`), recall, listing and `DELETE /api/memory/sessions/{id}`.
A binding applies whatever roles the key holds. It is read from the database on every request, so
binding, rebinding or unbinding takes effect on the key's next call.

## Who is not held to an agent

| Caller | Memory routes |
|---|---|
| Key bound to an agent | held to that agent (above) |
| Key, unbound, with the `ossian-admin` role | any agent, as before |
| Key, unbound, **not** confined to a namespace | any agent, as before |
| Key, unbound, **confined to a namespace** | **403** until bound, unless `ossian.memory.allow-unbound-confined-keys` is `true` |
| A signed-in person (Keycloak token), including the console's Memory page | any agent, as before |

Be clear about what this does not cover. Only a *bound* key is held to an agent. An unbound key
that is not namespace-confined and not admin can still reach every agent's memory; if you do not
want that, bind it. And a key with the `ossian-admin` role can change any key's binding through
`/api/admin/api-keys`, including its own, so binding an admin key limits an honest client but is
not a barrier against a compromised admin key. Issue narrow keys narrowly.

## Binding keys

Issue a key already bound (admin):

```bash
curl -X POST localhost:8081/api/admin/api-keys \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"support-agent","roles":["ossian-user"],"namespace":"support","agentId":"support-bot"}'
```

Bind a key that already exists (this is the upgrade path; the secret does not change):

```bash
curl -X PUT localhost:8081/api/admin/api-keys/$KEY_ID/agent \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"agentId":"support-bot"}'
```

Remove a binding (the key falls back to the unbound rules above):

```bash
curl -X DELETE localhost:8081/api/admin/api-keys/$KEY_ID/agent -H "Authorization: Bearer $TOKEN"
```

`GET /api/admin/api-keys` shows each key's `agentId` (`null` when unbound). Key ids come from
that listing.

### Choosing the `agentId`

It must equal, character for character, the `agentId` the agent already uses when it remembers and
recalls: `"support-bot"` and `"Support-Bot"` are different agents. To see which ids hold memory,
call `GET /api/memory/agents` with an admin token or from the console's **Memory** page.

Accepted: 1 to 128 characters, free text like the `agentId` on the memory routes, so an existing
agent can always be bound. Refused with a 400: empty or blank, longer than 128, any control
character (newline, tab, NUL), and leading or trailing whitespace (which would make the id look
right in a console and match nothing in the database).

There is no check that the agent exists yet. Binding a key to an agent that has no memories is
fine and is how a new agent is set up; the cost of a typo is a key that sees an empty memory, not
one that sees someone else's.

## Upgrading an existing deployment

1. **The migration runs by itself** at startup (Flyway `V13__api_key_agent_binding.sql`). It adds a
   nullable `api_keys.agent_id VARCHAR(128)`, a check that it is not blank, and a partial index on
   bound keys. No existing row is rewritten: every key you have today comes out **unbound**, so
   nothing needs re-issuing and no secret changes.
2. **One behaviour change can break a client.** After the upgrade, an existing API key that is
   confined to a namespace, has no admin role and is not bound gets a **403** on every
   `/api/memory` route, with a message telling it to bind the key. Previously it worked, which was
   the problem. Keys with no namespace, and keys with the admin role, behave exactly as before.
3. **Find the keys to bind** before you upgrade, with an admin token:

   ```bash
   curl -s localhost:8081/api/admin/api-keys -H "Authorization: Bearer $TOKEN" |
     jq '.[] | select(.active and .namespace != null and .agentId == null
                      and (.roles | index("ossian-admin") | not)) | {id, name, namespace}'
   ```

   (The `agentId` field is only present once the new version is running. Before the upgrade, the
   same filter without it lists every confined, non-admin, active key.)
4. **Bind each of them** with the `PUT` above once the new version is up. A client that is not
   changed keeps working, because it either names its own agent or omits `agentId`.
5. **If you cannot bind them yet**, set `ossian.memory.allow-unbound-confined-keys=true` (environment
   variable `OSSIAN_MEMORY_ALLOWUNBOUNDCONFINEDKEYS=true`, or `memory.allowUnboundConfinedKeys` in the
   Helm values). That restores the old behaviour for unbound confined keys only: it does not loosen
   a binding, and it does not change anyone else. Treat it as a stop-gap and remove it once the keys
   are bound, because with it set those keys can again read and erase every agent's memory.

### Rolling back

A release that predates this change ignores `agent_id`: the column stays, but nothing enforces
it, so the original problem returns for the duration of the rollback.

## Audit

Deleting a memory by id, and forgetting a session, each write a `memory.forgotten` audit row. The
row's actor and subject identify the acting key (`key:<name>` and `key:<key id>`; a person is
their username and token subject), and the detail carries `agent=<agent id>`; a session erase also
carries `forgotten=<count>`, including 0. An erase that was refused, or that matched nothing, writes
no `memory.forgotten` row. Issuing a key records the agent it was bound to, and binding or
unbinding records `apikey.updated`. Memory content is never written to the audit log.

Refused memory requests (the 403s above) are not audited. They appear in the access log of
whatever fronts the service.

## Troubleshooting

| Response | Cause | Fix |
|---|---|---|
| 403 `This API key is bound to agent 'X' and cannot access the memory of agent 'Y'` | the client names an agent other than the one its key is bound to | name `X`, omit `agentId`, or use a key bound to `Y` |
| 403 `... is confined to the 'N' namespace but is not bound to an agent` | an unbound, namespace-confined, non-admin key | bind it (`PUT /api/admin/api-keys/{id}/agent`) |
| 400 `agentId is required` | a caller that is not bound (a person, an admin key, an unconfined key) left `agentId` out | send an `agentId` |
| 404 on `DELETE /api/memory/{id}` | no such memory, or it belongs to an agent the key is not bound to | check the id and the key's binding |
