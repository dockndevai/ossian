-- Transformations: named prompts run over a whole source to produce an insight.
--
-- Distinct from asking a question. A question retrieves the few passages most like it; a
-- transformation reads the entire document. "Summarise this" cannot be answered from the three
-- chunks nearest to the word "summarise", which is why this does not go through the retriever
-- at all.

CREATE TABLE transformations (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       VARCHAR(128) NOT NULL,
    -- Stable identifier used in URLs and by the client library; the name is free to change.
    slug            VARCHAR(128) NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    prompt          TEXT         NOT NULL,
    -- Run automatically when a document finishes ingesting.
    apply_on_ingest BOOLEAN      NOT NULL DEFAULT FALSE,
    position        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_transformations_tenant_slug ON transformations (tenant_id, slug);
CREATE INDEX idx_transformations_tenant ON transformations (tenant_id, position);

CREATE TABLE insights (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id           VARCHAR(128) NOT NULL,
    document_id         UUID         NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    -- Nullable, and deliberately so: deleting a transformation must not delete the work it
    -- produced. The name and prompt below are what keep the output meaningful afterwards.
    transformation_id   UUID         REFERENCES transformations (id) ON DELETE SET NULL,
    transformation_name VARCHAR(200) NOT NULL,
    -- The exact prompt this output came from. Prompts get edited, and an insight whose prompt
    -- has since changed is otherwise unexplainable: you cannot tell whether it is stale or
    -- whether the model simply said something odd.
    prompt_used         TEXT         NOT NULL,
    output              TEXT         NOT NULL,
    model               VARCHAR(200),
    -- How many passes the input needed. More than one means the document was too long to read
    -- in a single call and the output is a combination, which is worth knowing when judging it.
    passes              INT          NOT NULL DEFAULT 1,
    duration_ms         BIGINT,
    created_by          VARCHAR(256),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_insights_document ON insights (tenant_id, document_id, created_at DESC);
CREATE INDEX idx_insights_tenant ON insights (tenant_id, created_at DESC);
