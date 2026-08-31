-- Whether an insight was recomputed or served from an earlier identical run.
--
-- Transformations are deterministic in the inputs that matter: the same source text, the same
-- prompt and the same model produce the same output. Recomputing that is the single most
-- expensive thing this application does, so identical runs are served from a cache.
--
-- These columns exist so the answer can say which it was. A cached result presented as fresh is
-- a small lie that becomes a large one the moment someone edits a prompt and cannot tell whether
-- the output reflects the change.

ALTER TABLE insights ADD COLUMN cache_key   VARCHAR(64);
ALTER TABLE insights ADD COLUMN from_cache  BOOLEAN NOT NULL DEFAULT FALSE;

-- Finding the previous identical run is the hot path when the cache is cold or has expired.
CREATE INDEX idx_insights_cache_key ON insights (tenant_id, cache_key, created_at DESC);
