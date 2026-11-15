-- Meeting & governance, step G2: who attended and whether there was quorum, online e-voting on
-- resolutions by secret ballot, and numbered resolutions.

-- Marking a meeting held freezes the member count and the quorum it needed.
ALTER TABLE meeting
    ADD COLUMN held_at          TIMESTAMP,
    ADD COLUMN held_by          BIGINT REFERENCES users(id),
    ADD COLUMN chairperson_id   BIGINT REFERENCES users(id),
    ADD COLUMN member_count     INTEGER,
    ADD COLUMN quorum_required  INTEGER,
    ADD COLUMN quorum_present   INTEGER;

-- Voting on an item: NOT_OPEN → OPEN → CLOSED. Tallies are counts only; who voted which way is never
-- stored (secret ballot). DEFERRED items are never voted on.
ALTER TABLE agenda_item
    ADD COLUMN proposed_by       BIGINT REFERENCES users(id),
    ADD COLUMN seconded_by       BIGINT REFERENCES users(id),
    ADD COLUMN voting_status     VARCHAR(10) NOT NULL DEFAULT 'NOT_OPEN',
    ADD COLUMN voting_opened_at  TIMESTAMP,
    ADD COLUMN voting_opened_by  BIGINT REFERENCES users(id),
    ADD COLUMN voting_closed_at  TIMESTAMP,
    ADD COLUMN voting_closed_by  BIGINT REFERENCES users(id),
    ADD COLUMN votes_for         INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN votes_against     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN votes_abstain     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN outcome           VARCHAR(10),
    ADD COLUMN resolution_number VARCHAR(40),
    ADD CONSTRAINT agenda_item_voting_check CHECK (voting_status IN ('NOT_OPEN', 'OPEN', 'CLOSED')),
    ADD CONSTRAINT agenda_item_outcome_check CHECK (outcome IS NULL OR outcome IN ('PASSED', 'REJECTED', 'DEFERRED'));

-- One row per vote cast: that this flat (general meetings) or this committee member voted, by whom and
-- when — not how.
CREATE TABLE ballot (
    id             BIGSERIAL   PRIMARY KEY,
    agenda_item_id BIGINT      NOT NULL REFERENCES agenda_item(id) ON DELETE CASCADE,
    voter_key      VARCHAR(30) NOT NULL,
    flat_id        BIGINT      REFERENCES flat(id) ON DELETE SET NULL,
    voted_by       BIGINT      NOT NULL REFERENCES users(id),
    voted_at       TIMESTAMP   NOT NULL,
    UNIQUE (agenda_item_id, voter_key)
);

-- Who was present (per person; quorum counts member flats or committee members from these).
CREATE TABLE meeting_attendance (
    id          BIGSERIAL PRIMARY KEY,
    meeting_id  BIGINT    NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    user_id     BIGINT    NOT NULL REFERENCES users(id),
    recorded_by BIGINT    NOT NULL REFERENCES users(id),
    recorded_at TIMESTAMP NOT NULL,
    UNIQUE (meeting_id, user_id)
);

-- Resolution numbers: per society, kind of meeting and financial year (AGM/2026-27/3).
CREATE TABLE resolution_counter (
    society_id     BIGINT      NOT NULL REFERENCES society(id) ON DELETE CASCADE,
    prefix         VARCHAR(10) NOT NULL,
    financial_year VARCHAR(10) NOT NULL,
    last_number    INTEGER     NOT NULL,
    PRIMARY KEY (society_id, prefix, financial_year)
);

INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('GOVERNANCE','VOTE',NULL,'CREATE','GOVERNANCE.VOTE.CREATE','Vote on resolutions (owner side of a member flat; committee members at committee meetings)'),
('GOVERNANCE','MINUTES',NULL,'CREATE','GOVERNANCE.MINUTES.CREATE','Run a meeting: attendance, open and close voting, defer items, draft minutes');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'GOVERNANCE.VOTE.CREATE' WHERE r.code = 'RESIDENT';

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'GOVERNANCE.MINUTES.CREATE' WHERE r.code = 'SECRETARY';
