-- Meeting & governance (module 26, step G1): general body meetings (AGM, SGM) and managing committee
-- meetings, their agendas, the notice sent to the invitees, RSVPs, cancelling and adjourning.
-- Attendance, e-voting and minutes come in later steps.

-- DRAFT (being prepared) → NOTICE_SENT (published to the invitees) → later HELD and MINUTES_PUBLISHED;
-- CANCELLED or ADJOURNED (to a new meeting, linked both ways) before it is held.
CREATE TABLE meeting (
    id                  BIGSERIAL    PRIMARY KEY,
    society_id          BIGINT       NOT NULL REFERENCES society(id),
    kind                VARCHAR(10)  NOT NULL,
    title               VARCHAR(200) NOT NULL,
    description         VARCHAR(4000),
    scheduled_at        TIMESTAMP    NOT NULL,
    place               VARCHAR(300),
    online_link         VARCHAR(500),
    status              VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    notice_sent_at      TIMESTAMP,
    notice_sent_by      BIGINT       REFERENCES users(id),
    -- Given when the notice went out later than the society's notice period allows.
    short_notice_reason VARCHAR(500),
    cancelled_at        TIMESTAMP,
    cancelled_by        BIGINT       REFERENCES users(id),
    cancel_reason       VARCHAR(500),
    adjourned_from_id   BIGINT       REFERENCES meeting(id),
    adjourned_to_id     BIGINT       REFERENCES meeting(id),
    adjourn_reason      VARCHAR(500),
    reminder_sent_at    TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL,
    created_by          BIGINT       NOT NULL REFERENCES users(id),
    updated_at          TIMESTAMP,
    updated_by          BIGINT       REFERENCES users(id),
    CONSTRAINT meeting_kind_check CHECK (kind IN ('AGM', 'SGM', 'COMMITTEE')),
    CONSTRAINT meeting_status_check CHECK (status IN ('DRAFT', 'NOTICE_SENT', 'CANCELLED', 'ADJOURNED', 'HELD', 'MINUTES_PUBLISHED')),
    CONSTRAINT meeting_where_check CHECK (place IS NOT NULL OR online_link IS NOT NULL),
    CONSTRAINT meeting_cancelled_check CHECK (status <> 'CANCELLED' OR cancel_reason IS NOT NULL),
    CONSTRAINT meeting_adjourned_check CHECK (status <> 'ADJOURNED' OR adjourned_to_id IS NOT NULL)
);
CREATE INDEX meeting_society_idx ON meeting (society_id, scheduled_at);
-- The reminder job looks for meetings whose notice went out and whose reminder hasn't.
CREATE INDEX meeting_reminder_idx ON meeting (scheduled_at) WHERE status = 'NOTICE_SENT' AND reminder_sent_at IS NULL;

-- The agenda, in order. A proposed resolution is voted on from step G2.
CREATE TABLE agenda_item (
    id              BIGSERIAL     PRIMARY KEY,
    meeting_id      BIGINT        NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    position        INTEGER       NOT NULL,
    title           VARCHAR(300)  NOT NULL,
    description     VARCHAR(4000),
    resolution_text VARCHAR(4000),
    UNIQUE (meeting_id, position)
);

-- Who says they'll come: a headcount guide only, not attendance.
CREATE TABLE meeting_rsvp (
    id          BIGSERIAL   PRIMARY KEY,
    meeting_id  BIGINT      NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    user_id     BIGINT      NOT NULL REFERENCES users(id),
    response    VARCHAR(5)  NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    UNIQUE (meeting_id, user_id),
    CONSTRAINT meeting_rsvp_response_check CHECK (response IN ('YES', 'NO', 'MAYBE'))
);

INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('GOVERNANCE','MEETING',NULL,'VIEW','GOVERNANCE.MEETING.VIEW','See general meetings (AGM, SGM), their agendas and minutes; RSVP'),
('GOVERNANCE','MEETING',NULL,'CREATE','GOVERNANCE.MEETING.CREATE','Prepare meetings and their agendas'),
('GOVERNANCE','MEETING',NULL,'UPDATE','GOVERNANCE.MEETING.UPDATE','Send meeting notices, cancel and adjourn meetings'),
('GOVERNANCE','COMMITTEE',NULL,'VIEW','GOVERNANCE.COMMITTEE.VIEW','See managing committee meetings and their minutes');

-- Residents (and everyone above, by inheritance) see general meetings; the committee sees its own
-- meetings; the secretary and above run them. Guards don't see meetings.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'GOVERNANCE.MEETING.VIEW' WHERE r.code = 'RESIDENT';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'GOVERNANCE.COMMITTEE.VIEW' WHERE r.code = 'COMMITTEE';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code IN ('GOVERNANCE.MEETING.CREATE', 'GOVERNANCE.MEETING.UPDATE')
WHERE r.code = 'SECRETARY';
