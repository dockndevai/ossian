-- A per-key request ceiling.
--
-- On the key rather than global because the callers differ: a bulk importer legitimately makes
-- thousands of requests an hour, an interactive agent makes a few dozen, and one limit that suits
-- both suits neither. Null means the installation default applies.
ALTER TABLE api_keys ADD COLUMN requests_per_minute INT;
