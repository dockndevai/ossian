-- Namespaces, runtime settings, and an inbound event log.
--
-- Namespaces partition a tenant's corpus. Tenancy is a security boundary and comes from the
-- token; a namespace is an organisational one and comes from the request, so the two are
-- separate columns rather than a composite key. A tenant can see all of its namespaces; it can
-- never see another tenant's, whatever namespace is asked for.

CREATE TABLE namespaces (
    id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id   VARCHAR(128) NOT NULL,
    -- Slug, not free text: it is passed to the retriever as a filter value and shows up in URLs.
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_namespaces_tenant_name ON namespaces (tenant_id, name);

-- Every tenant gets a default namespace so nothing has to special-case "no namespace".
INSERT INTO namespaces (tenant_id, name, description)
SELECT DISTINCT tenant_id, 'default', 'Everything not filed elsewhere'
FROM documents
ON CONFLICT DO NOTHING;

ALTER TABLE documents ADD COLUMN namespace VARCHAR(128) NOT NULL DEFAULT 'default';
CREATE INDEX idx_documents_namespace ON documents (tenant_id, namespace, created_at DESC);

-- The uniqueness rule moves with the namespace: the same file may legitimately exist in two
-- namespaces, but not twice in one.
DROP INDEX IF EXISTS ux_documents_tenant_hash;
CREATE UNIQUE INDEX ux_documents_tenant_ns_hash ON documents (tenant_id, namespace, content_hash);

ALTER TABLE query_log ADD COLUMN namespace VARCHAR(128);

-- Runtime settings, per tenant.
--
-- These shadow the values in application.yml. The file supplies the default; a row here
-- overrides it for one tenant. Storing them as a narrow key/value table rather than columns
-- means adding a setting is a code change, not a migration.
CREATE TABLE tenant_settings (
    tenant_id  VARCHAR(128) NOT NULL,
    key        VARCHAR(128) NOT NULL,
    value      TEXT         NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by VARCHAR(256),
    PRIMARY KEY (tenant_id, key)
);

-- Inbound events, for change-data-capture style ingestion.
--
-- The event id is supplied by the caller and unique per tenant, which is what makes delivery
-- idempotent: a CDC pipeline that redelivers after a crash is the normal case, not the
-- exception, and a duplicate must not produce a duplicate document.
CREATE TABLE ingest_events (
    id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id     VARCHAR(128) NOT NULL,
    event_id      VARCHAR(256) NOT NULL,
    namespace     VARCHAR(128) NOT NULL DEFAULT 'default',
    operation     VARCHAR(16)  NOT NULL,
    external_id   VARCHAR(512) NOT NULL,
    source        VARCHAR(128),
    document_id   UUID         REFERENCES documents (id) ON DELETE SET NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'ACCEPTED',
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_ingest_events_tenant_event ON ingest_events (tenant_id, event_id);
CREATE INDEX idx_ingest_events_tenant ON ingest_events (tenant_id, created_at DESC);

-- An externally-keyed document can be updated in place by a later event. Nullable, because
-- documents uploaded through the UI have no external identity.
ALTER TABLE documents ADD COLUMN external_id VARCHAR(512);
ALTER TABLE documents ADD COLUMN source VARCHAR(128);
CREATE UNIQUE INDEX ux_documents_tenant_ns_external
    ON documents (tenant_id, namespace, external_id) WHERE external_id IS NOT NULL;
