ALTER TABLE query_log ADD COLUMN first_token_ms BIGINT;
ALTER TABLE query_log ADD COLUMN failure VARCHAR(32);
