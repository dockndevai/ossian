-- Machine credentials.
--
-- Everything here was reachable only with a Keycloak user token from an interactive login, which
-- an agent cannot perform. A key is what lets a process authenticate as a tenant without a human
-- at a browser.
--
-- Only the hash is stored. A key is shown once, at creation, and is unrecoverable afterwards --
-- a table an operator can read keys out of is a table worth stealing.

CREATE TABLE api_keys (
    id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id    VARCHAR(128) NOT NULL,
    name         VARCHAR(200) NOT NULL,
    -- SHA-256 hex. VARCHAR, not CHAR: CHAR space-pads in Postgres, and a padded hash never
    -- compares equal to the one computed from the presented key.
    key_hash     VARCHAR(64)  NOT NULL,
    -- The leading characters, kept in clear so a key can be recognised in a list without
    -- revealing enough to use.
    key_prefix   VARCHAR(24)  NOT NULL,
    -- Comma-separated realm roles this key carries. Keys are usually narrower than the person
    -- who made them: an ingestion pipeline needs to write documents, not administer the tenant.
    roles        VARCHAR(500) NOT NULL DEFAULT 'ossian-user',
    -- Optional: confine the key to one namespace, so a leaked pipeline key cannot read the rest
    -- of the corpus.
    namespace    VARCHAR(128),
    created_by   VARCHAR(256),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ
);

-- The lookup on every authenticated request, so it is the one index that has to exist.
CREATE UNIQUE INDEX ux_api_keys_hash ON api_keys (key_hash);
CREATE INDEX idx_api_keys_tenant ON api_keys (tenant_id, created_at DESC);
