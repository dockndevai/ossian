-- Remove multi-tenancy.
--
-- This is a single-company deployment: one organisation runs one Ossian over its own documents.
-- Namespaces already partition a corpus, and a tenant column on top of that was a second
-- partitioning concept earning nothing — every query carried it, every index led with it, and no
-- deployment ever had more than one value in it.
--
-- IRREVERSIBLE, and not only in the schema sense: if more than one tenant_id were present, this
-- merges their rows into one corpus. That is correct here (one tenant holds every document) and
-- would be a data breach on an installation that genuinely had several, which is why this is a
-- deliberate migration rather than something that could be inferred and applied automatically.
--
-- Dropping a column takes its indexes with it, so the ones still worth having are recreated
-- afterwards without the leading tenant column.

-- Settings were per tenant; with one tenant they are simply the settings.
ALTER TABLE tenant_settings DROP CONSTRAINT tenant_settings_pkey;
ALTER TABLE tenant_settings DROP COLUMN tenant_id;
ALTER TABLE tenant_settings RENAME TO settings;
ALTER TABLE settings ADD PRIMARY KEY (key);

ALTER TABLE documents        DROP COLUMN tenant_id;
ALTER TABLE namespaces       DROP COLUMN tenant_id;
ALTER TABLE transformations  DROP COLUMN tenant_id;
ALTER TABLE insights         DROP COLUMN tenant_id;
ALTER TABLE ingestion_jobs   DROP COLUMN tenant_id;
ALTER TABLE ingest_events    DROP COLUMN tenant_id;
ALTER TABLE query_log        DROP COLUMN tenant_id;
ALTER TABLE agent_memories   DROP COLUMN tenant_id;
ALTER TABLE api_keys         DROP COLUMN tenant_id;

-- Uniqueness that used to be per tenant is now global.
CREATE UNIQUE INDEX ux_namespaces_name        ON namespaces (name);
CREATE UNIQUE INDEX ux_transformations_slug   ON transformations (slug);
CREATE UNIQUE INDEX ux_documents_ns_hash      ON documents (namespace, content_hash);
CREATE UNIQUE INDEX ux_documents_ns_external  ON documents (namespace, external_id)
    WHERE external_id IS NOT NULL;
CREATE UNIQUE INDEX ux_ingest_events_event    ON ingest_events (event_id);
CREATE UNIQUE INDEX ux_agent_memories_dedupe  ON agent_memories
    (agent_id, coalesce(session_id, ''), content_hash);

-- Ordinary lookups, minus the column that led all of them.
CREATE INDEX idx_documents_status        ON documents (status);
CREATE INDEX idx_documents_namespace     ON documents (namespace, created_at DESC);
CREATE INDEX idx_transformations_order   ON transformations (position);
CREATE INDEX idx_insights_document       ON insights (document_id, created_at DESC);
CREATE INDEX idx_insights_cache_key      ON insights (cache_key, created_at DESC);
CREATE INDEX idx_jobs_created            ON ingestion_jobs (created_at DESC);
CREATE INDEX idx_ingest_events_created   ON ingest_events (created_at DESC);
CREATE INDEX idx_query_log_created       ON query_log (created_at DESC);
CREATE INDEX idx_query_log_unanswered    ON query_log (created_at DESC) WHERE answered = false;
CREATE INDEX idx_agent_memories_scope    ON agent_memories (agent_id, created_at DESC);
CREATE INDEX idx_agent_memories_session  ON agent_memories (agent_id, session_id);
CREATE INDEX idx_agent_memories_subject  ON agent_memories (subject);
CREATE INDEX idx_api_keys_created        ON api_keys (created_at DESC);

-- Chunks carried the tenant in their metadata so retrieval could filter on it. Nothing filters
-- on it now, and leaving it would be a field that looks meaningful and is not.
--
-- Guarded on the table existing, for the same reason V3 is: vector_store belongs to Spring AI's
-- initialize-schema, which runs after Flyway. On a fresh database there are no chunks to clean.
DO $$
BEGIN
    IF to_regclass('public.vector_store') IS NULL THEN
        RETURN;
    END IF;

    UPDATE vector_store
    SET metadata = ((metadata::jsonb) - 'tenant_id')::json
    WHERE (metadata::jsonb) ? 'tenant_id';
END $$;
