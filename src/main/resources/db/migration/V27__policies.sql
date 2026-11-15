-- Rules & policies (module 24, steps P1–P2): the society's rule book. A policy has versions; each
-- version is drafted, approved (or published directly when the society turns approval off), and kept
-- forever once published. Residents acknowledge the policies that ask for it.

-- Per-society categories. The defaults are inserted the first time a society's categories are read;
-- hidden ones are not offered for new policies. They go with the society (no history of their own).
CREATE TABLE policy_category (
    id         BIGSERIAL    PRIMARY KEY,
    society_id BIGINT       NOT NULL REFERENCES society(id) ON DELETE CASCADE,
    name       VARCHAR(60)  NOT NULL,
    position   INTEGER      NOT NULL,
    hidden     BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX policy_category_name ON policy_category (society_id, lower(name));

-- A policy is DRAFT until its first version is published, then ACTIVE; ARCHIVED takes it out of force
-- (and off the rules page) without losing its history. A policy is history: it keeps its society.
CREATE TABLE policy (
    id                       BIGSERIAL    PRIMARY KEY,
    society_id               BIGINT       NOT NULL REFERENCES society(id),
    category_id              BIGINT       NOT NULL REFERENCES policy_category(id),
    title                    VARCHAR(200) NOT NULL,
    status                   VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    requires_acknowledgement BOOLEAN      NOT NULL DEFAULT false,
    created_at               TIMESTAMP    NOT NULL,
    created_by               BIGINT       NOT NULL REFERENCES users(id),
    updated_at               TIMESTAMP,
    updated_by               BIGINT       REFERENCES users(id),
    archived_at              TIMESTAMP,
    archived_by              BIGINT       REFERENCES users(id),
    CONSTRAINT policy_status_check CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);
CREATE INDEX policy_society_idx ON policy (society_id, status);

-- DRAFT → PENDING (submitted) → PUBLISHED, sent back to DRAFT with a reason, or withdrawn. Publishing a
-- version makes every earlier published one SUPERSEDED. A published version is never changed again.
CREATE TABLE policy_version (
    id                BIGSERIAL     PRIMARY KEY,
    policy_id         BIGINT        NOT NULL REFERENCES policy(id) ON DELETE CASCADE,
    number            INTEGER       NOT NULL,
    body              TEXT          NOT NULL DEFAULT '',
    change_summary    VARCHAR(500),
    -- Null on a draft means "the day it is published".
    effective_from    DATE,
    -- Asks residents to acknowledge again (only meaningful when the policy requires acknowledgement).
    material_change   BOOLEAN       NOT NULL DEFAULT true,
    status            VARCHAR(10)   NOT NULL DEFAULT 'DRAFT',
    attachment_key    VARCHAR(100),
    attachment_name   VARCHAR(255),
    attachment_type   VARCHAR(100),
    attachment_size   INTEGER,
    created_at        TIMESTAMP     NOT NULL,
    created_by        BIGINT        NOT NULL REFERENCES users(id),
    updated_at        TIMESTAMP,
    submitted_at      TIMESTAMP,
    reviewed_at       TIMESTAMP,
    reviewed_by       BIGINT        REFERENCES users(id),
    review_reason     VARCHAR(500),
    published_at      TIMESTAMP,
    published_by      BIGINT        REFERENCES users(id),
    UNIQUE (policy_id, number),
    CONSTRAINT policy_version_status_check CHECK (status IN ('DRAFT', 'PENDING', 'PUBLISHED', 'SUPERSEDED')),
    CONSTRAINT policy_version_published_check CHECK (
        status IN ('DRAFT', 'PENDING') OR (published_at IS NOT NULL AND effective_from IS NOT NULL)
    ),
    CONSTRAINT policy_version_body_check CHECK (length(body) <= 50000)
);
-- One version being worked on (DRAFT or PENDING) per policy.
CREATE UNIQUE INDEX policy_version_one_open ON policy_version (policy_id) WHERE status IN ('DRAFT', 'PENDING');
CREATE INDEX policy_version_pending_idx ON policy_version (status) WHERE status = 'PENDING';

-- "I have read and accept", per person, against the version that asked for it.
CREATE TABLE policy_acknowledgement (
    id              BIGSERIAL PRIMARY KEY,
    version_id      BIGINT    NOT NULL REFERENCES policy_version(id) ON DELETE CASCADE,
    user_id         BIGINT    NOT NULL REFERENCES users(id),
    flat_id         BIGINT    REFERENCES flat(id) ON DELETE SET NULL,
    acknowledged_at TIMESTAMP NOT NULL,
    UNIQUE (version_id, user_id)
);

-- Reminders sent to a flat, so the same flat isn't reminded again too soon.
CREATE TABLE policy_reminder (
    id         BIGSERIAL PRIMARY KEY,
    version_id BIGINT    NOT NULL REFERENCES policy_version(id) ON DELETE CASCADE,
    flat_id    BIGINT    NOT NULL REFERENCES flat(id) ON DELETE CASCADE,
    sent_at    TIMESTAMP NOT NULL,
    sent_by    BIGINT    NOT NULL REFERENCES users(id)
);
CREATE INDEX policy_reminder_flat_idx ON policy_reminder (version_id, flat_id, sent_at DESC);

INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('POLICY','RULE',NULL,'VIEW','POLICY.RULE.VIEW','Read the society''s published rules and policies'),
('POLICY','RULE',NULL,'CREATE','POLICY.RULE.CREATE','Draft policies and see drafts awaiting approval'),
('POLICY','RULE',NULL,'UPDATE','POLICY.RULE.UPDATE','Edit, submit and withdraw own policy drafts'),
('POLICY','RULE',NULL,'APPROVE','POLICY.RULE.APPROVE','Approve, send back, publish and archive policies'),
('POLICY','ACKNOWLEDGEMENT',NULL,'CREATE','POLICY.ACKNOWLEDGEMENT.CREATE','Acknowledge a policy'),
('POLICY','ACKNOWLEDGEMENT',NULL,'VIEW','POLICY.ACKNOWLEDGEMENT.VIEW','See who has acknowledged a policy and remind the rest');

-- RESIDENT and GUARD read; COMMITTEE (and so CHAIRMAN, SECRETARY, ADMIN) drafts and follows up;
-- CHAIRMAN and above approve. Acknowledging also needs a flat role (checked in code).
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('POLICY.RULE.VIEW', 'POLICY.ACKNOWLEDGEMENT.CREATE')
WHERE r.code = 'RESIDENT';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('POLICY.RULE.VIEW') WHERE r.code = 'GUARD';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN (
    'POLICY.RULE.CREATE', 'POLICY.RULE.UPDATE', 'POLICY.ACKNOWLEDGEMENT.VIEW'
) WHERE r.code = 'COMMITTEE';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('POLICY.RULE.APPROVE') WHERE r.code = 'CHAIRMAN';
