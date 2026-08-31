-- Who did what.
--
-- The question an audit log answers is asked after something has gone wrong, about a moment that
-- has already passed — so it has to have been recording all along, and it has to be believable
-- afterwards. Two consequences shape this table.
--
-- Append-only. There is no update path in the application and no reason for one: a record that
-- can be edited answers "what does someone want me to think happened", which is a different and
-- much less useful question.
--
-- It records the actor as presented at the time, not a foreign key to a user. People leave and
-- keys are revoked; a row that says "key:nightly-import" still means something after that key is
-- gone, where a dangling reference does not.

CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- "admin", or "key:nightly-import". Deliberately the display form.
    actor        VARCHAR(256) NOT NULL,
    -- Stable identity behind the actor, for correlating a renamed user or a rotated key.
    subject      VARCHAR(256),
    -- Whether a person or a process did it, which is usually the first thing asked.
    machine      BOOLEAN      NOT NULL DEFAULT FALSE,
    action       VARCHAR(64)  NOT NULL,
    -- What it was done to: a document id, a namespace, a key prefix.
    target_type  VARCHAR(64),
    target_id    VARCHAR(256),
    namespace    VARCHAR(128),
    -- Free-form context, kept small on purpose: an audit row is not a debug log, and one that
    -- carries request bodies becomes a second copy of the data it is meant to be watching.
    detail       VARCHAR(1000),
    outcome      VARCHAR(32)  NOT NULL DEFAULT 'success',
    ip           VARCHAR(64)
);

-- Reading is always "recently, filtered", so time leads every index.
CREATE INDEX idx_audit_at ON audit_log (at DESC);
CREATE INDEX idx_audit_actor ON audit_log (actor, at DESC);
CREATE INDEX idx_audit_action ON audit_log (action, at DESC);
CREATE INDEX idx_audit_target ON audit_log (target_type, target_id, at DESC);
