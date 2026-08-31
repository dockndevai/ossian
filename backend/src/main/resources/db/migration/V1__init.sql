-- Ossian initial schema.
--
-- Two stores in one database: relational tables here for documents and jobs, and a pgvector
-- table for the embeddings. Keeping them together means a document and its chunks can be
-- deleted in a single transaction, which is what stops orphaned vectors answering questions
-- about a document that no longer exists.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       VARCHAR(128)  NOT NULL,
    filename        VARCHAR(512)  NOT NULL,
    content_type    VARCHAR(128),
    size_bytes      BIGINT        NOT NULL,
    -- SHA-256 hex of the file, so a re-upload is detected without re-embedding.
    -- VARCHAR not CHAR: Postgres space-pads CHAR to its declared width, which silently
    -- breaks equality against an unpadded hash.
    content_hash    VARCHAR(64)   NOT NULL,
    title           VARCHAR(512),
    status          VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    chunk_count     INT           NOT NULL DEFAULT 0,
    error_message   TEXT,
    uploaded_by     VARCHAR(256),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Every read path filters by tenant first; without this index that becomes a sequential scan
-- as soon as one tenant has a large corpus.
CREATE INDEX idx_documents_tenant        ON documents (tenant_id, created_at DESC);
CREATE INDEX idx_documents_status        ON documents (tenant_id, status);
-- Same file uploaded twice into one tenant is a no-op rather than a duplicate corpus entry.
CREATE UNIQUE INDEX ux_documents_tenant_hash ON documents (tenant_id, content_hash);

-- Original bytes, kept in their own table so listing documents never drags blobs into memory.
-- Needed because re-indexing (say, after changing chunk size) has to re-parse the source.
CREATE TABLE document_content (
    document_id UUID PRIMARY KEY REFERENCES documents (id) ON DELETE CASCADE,
    content     BYTEA NOT NULL
);

CREATE TABLE ingestion_jobs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id       VARCHAR(128) NOT NULL,
    document_id     UUID         REFERENCES documents (id) ON DELETE CASCADE,
    type            VARCHAR(32)  NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    chunks_written  INT          NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_jobs_tenant  ON ingestion_jobs (tenant_id, created_at DESC);
CREATE INDEX idx_jobs_status  ON ingestion_jobs (status);

-- Retrieval telemetry. This is what makes the admin side worth having: without recording what
-- was retrieved and how well it scored, "is retrieval getting worse?" is unanswerable.
CREATE TABLE query_log (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    tenant_id         VARCHAR(128) NOT NULL,
    subject           VARCHAR(256),
    question          TEXT         NOT NULL,
    chunks_retrieved  INT          NOT NULL DEFAULT 0,
    top_score         DOUBLE PRECISION,
    answered          BOOLEAN      NOT NULL DEFAULT TRUE,
    latency_ms        BIGINT,
    prompt_tokens     INT,
    completion_tokens INT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_query_log_tenant ON query_log (tenant_id, created_at DESC);
-- Finding the questions the corpus could not answer is the main input to knowing what to ingest next.
CREATE INDEX idx_query_log_unanswered ON query_log (tenant_id, answered) WHERE answered = FALSE;
