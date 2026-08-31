-- Backfill the namespace onto chunks that were embedded before namespaces existed.
--
-- V2 added the column to documents, but the vector store keeps its own copy of the metadata in
-- a JSON column and nothing rewrote it. The retriever filters on that copy, so every existing
-- chunk was invisible to a namespace-scoped question while still being visible to an unscoped
-- one — the corpus appeared to empty itself the moment anyone used the feature.
--
-- Guarded on the table existing, because Flyway does not own it: vector_store is created by
-- Spring AI's initialize-schema at startup, which happens after migrations run. On a fresh
-- database this is a no-op and correctly so — there are no pre-namespace chunks to repair.
DO $$
BEGIN
    IF to_regclass('public.vector_store') IS NULL THEN
        RETURN;
    END IF;

    -- Matched back through document_id, which every chunk carries because deletion depends on it.
    UPDATE vector_store v
    SET metadata = ((v.metadata::jsonb) || jsonb_build_object('namespace', d.namespace))::json
    FROM documents d
    WHERE v.metadata->>'document_id' = d.id::text
      AND v.metadata->>'namespace' IS NULL;

    -- A chunk whose document row is gone should not exist — deletion removes both in one
    -- transaction — but an orphan with no namespace would be permanently unreachable rather
    -- than merely wrong, so give it the default and let the corpus view show it.
    UPDATE vector_store
    SET metadata = ((metadata::jsonb) || jsonb_build_object('namespace', 'default'))::json
    WHERE metadata->>'namespace' IS NULL;
END $$;
