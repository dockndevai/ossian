-- Bind an API key to one agent.
--
-- /api/memory is keyed by an agentId that the CALLER supplies, so until now any valid key could
-- list, recall and erase every agent's memories by naming them. A key bound to an agent has that
-- choice taken away: memory routes are forced to this agent for it (see MemoryAccess).
--
-- Nullable, and null means "not bound", so every existing key keeps the row it has and nothing
-- has to be re-issued on upgrade. What an unbound key may do on memory routes is decided in code,
-- not here: an admin key, or a key with no namespace confinement, behaves exactly as before; a
-- namespace-confined unbound key is refused unless ossian.memory.allow-unbound-confined-keys is
-- set. Binding is how such a key regains access.
--
-- The length matches agent_memories.agent_id (128), so any agent that already holds memories can
-- be bound. The charset is deliberately NOT narrowed beyond "not blank": agent ids in existing
-- deployments are free text, and a binding that cannot spell one of them could never be used.
ALTER TABLE api_keys ADD COLUMN agent_id VARCHAR(128);

ALTER TABLE api_keys ADD CONSTRAINT ck_api_keys_agent_id_not_blank
    CHECK (agent_id IS NULL OR btrim(agent_id) <> '');

-- Partial: most keys are unbound, and the lookup that matters ("which keys are bound to this
-- agent", for an operator tidying up before retiring it) only ever asks about bound ones.
CREATE INDEX idx_api_keys_agent ON api_keys (agent_id) WHERE agent_id IS NOT NULL;
