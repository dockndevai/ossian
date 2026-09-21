
-- A model change cannot cast existing vectors to a different dimension. Preserve the
-- memories themselves, clear only their stale vectors, and let the operator re-embed
-- them with the newly configured model. A same-dimension upgrade keeps its vectors.
DO $$
DECLARE
    current_dimension integer;
BEGIN
    SELECT a.atttypmod
      INTO current_dimension
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
     WHERE c.relname = 'agent_memories'
       AND a.attname = 'embedding'
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF current_dimension IS NULL THEN
        RAISE EXCEPTION 'agent_memories.embedding is not a dimensioned pgvector column';
    END IF;

    IF current_dimension <> ${embedDimensions} THEN
        DROP INDEX IF EXISTS idx_agent_memories_vector;
        UPDATE agent_memories SET embedding = NULL WHERE embedding IS NOT NULL;
        ALTER TABLE agent_memories
            ALTER COLUMN embedding TYPE VECTOR(${embedDimensions})
            USING NULL::VECTOR(${embedDimensions});
        CREATE INDEX idx_agent_memories_vector ON agent_memories
            USING hnsw (embedding vector_cosine_ops);
    END IF;
END $$;
