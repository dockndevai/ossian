-- Agent memory.
--
-- Deliberately NOT in vector_store. Memories and documents are both embedded text, which makes
-- sharing a table tempting, and wrong: document retrieval filters on tenant and namespace, so
-- memories would be returned as citations in ordinary answers. "According to [1] the user said
-- they prefer dark mode" is not a fact from the corpus, and there would be no way to tell it
-- apart from one.
--
-- The other difference is lifetime. A document is durable until deleted; a memory can be about
-- one session and worthless after it, which is why expiry is a column rather than a policy.

CREATE TABLE agent_memories (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id      VARCHAR(128) NOT NULL,
    -- Which agent this belongs to. Two agents in one tenant should not read each other's
    -- recollections by default.
    agent_id       VARCHAR(128) NOT NULL,
    -- Optional narrowing. A session memory is scratch; a subject memory is what the agent knows
    -- about one person and outlives any single conversation.
    session_id     VARCHAR(128),
    subject        VARCHAR(256),
    kind           VARCHAR(32)  NOT NULL DEFAULT 'fact',
    content        TEXT         NOT NULL,
    -- Caller-supplied, opaque here, returned on retrieval so an agent can carry its own structure.
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- Weighting the agent controls, for facts that should win over merely similar ones.
    importance     REAL         NOT NULL DEFAULT 1.0,
    embedding      VECTOR(768),
    -- Deduplication: writing the same sentence twice should update, not accumulate. An agent
    -- that re-states what it already knows on every turn would otherwise drown itself.
    content_hash   VARCHAR(64)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at   TIMESTAMPTZ,
    use_count      INT          NOT NULL DEFAULT 0,
    expires_at     TIMESTAMPTZ
);

-- One row per identical statement within an agent's own scope.
CREATE UNIQUE INDEX ux_agent_memories_dedupe
    ON agent_memories (tenant_id, agent_id, coalesce(session_id, ''), content_hash);

CREATE INDEX idx_agent_memories_scope ON agent_memories (tenant_id, agent_id, created_at DESC);
CREATE INDEX idx_agent_memories_session ON agent_memories (tenant_id, agent_id, session_id);
CREATE INDEX idx_agent_memories_subject ON agent_memories (tenant_id, subject);
-- Expiry is checked on every read, so it needs to be cheap.
CREATE INDEX idx_agent_memories_expiry ON agent_memories (expires_at) WHERE expires_at IS NOT NULL;

CREATE INDEX idx_agent_memories_vector ON agent_memories
    USING hnsw (embedding vector_cosine_ops);
