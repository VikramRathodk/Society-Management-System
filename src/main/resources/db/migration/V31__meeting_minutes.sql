-- Meeting & governance, step G3: minutes. The secretary drafts them after the meeting is held, the
-- chairman (or another approver, never the drafter) approves them, and they are published: general
-- meeting minutes to every member, committee meeting minutes to the committee. Published minutes are
-- never edited; corrections are dated addenda. Agenda items can refer to a policy.

CREATE TABLE meeting_minutes (
    id              BIGSERIAL    PRIMARY KEY,
    meeting_id      BIGINT       NOT NULL UNIQUE REFERENCES meeting(id) ON DELETE CASCADE,
    body            TEXT         NOT NULL DEFAULT '',
    status          VARCHAR(10)  NOT NULL DEFAULT 'DRAFT',
    attachment_key  VARCHAR(100),
    attachment_name VARCHAR(255),
    attachment_type VARCHAR(100),
    attachment_size INTEGER,
    drafted_by      BIGINT       NOT NULL REFERENCES users(id),
    drafted_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP,
    submitted_at    TIMESTAMP,
    reviewed_by     BIGINT       REFERENCES users(id),
    reviewed_at     TIMESTAMP,
    review_reason   VARCHAR(500),
    approved_by     BIGINT       REFERENCES users(id),
    published_at    TIMESTAMP,
    announced_notice_id BIGINT,
    CONSTRAINT meeting_minutes_status_check CHECK (status IN ('DRAFT', 'PENDING', 'PUBLISHED')),
    CONSTRAINT meeting_minutes_body_check CHECK (length(body) <= 100000),
    CONSTRAINT meeting_minutes_published_check CHECK (status <> 'PUBLISHED' OR (approved_by IS NOT NULL AND published_at IS NOT NULL))
);

CREATE TABLE minutes_addendum (
    id          BIGSERIAL     PRIMARY KEY,
    minutes_id  BIGINT        NOT NULL REFERENCES meeting_minutes(id) ON DELETE CASCADE,
    text        VARCHAR(4000) NOT NULL,
    created_by  BIGINT        NOT NULL REFERENCES users(id),
    created_at  TIMESTAMP     NOT NULL
);

-- A resolution can refer to a policy ("adopts version 3 of the Parking rules"): a reference only.
ALTER TABLE agenda_item ADD COLUMN policy_id BIGINT REFERENCES policy(id) ON DELETE SET NULL;

INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('GOVERNANCE','MINUTES',NULL,'APPROVE','GOVERNANCE.MINUTES.APPROVE','Approve and publish meeting minutes (never one''s own draft)');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'GOVERNANCE.MINUTES.APPROVE' WHERE r.code = 'CHAIRMAN';
