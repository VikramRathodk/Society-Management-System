-- Subscription & billing, step S4: every billing email, sent or not (to see what reached the society).
CREATE TABLE email_log (
    id          BIGSERIAL    PRIMARY KEY,
    to_address  VARCHAR(255),
    -- No foreign keys: an audit log, written in its own transaction after the billing change committed.
    user_id     BIGINT,
    society_id  BIGINT,
    subject     VARCHAR(300) NOT NULL,
    kind        VARCHAR(20)  NOT NULL,
    related_id  BIGINT,
    status      VARCHAR(10)  NOT NULL,
    error       VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT email_log_kind_check CHECK (kind IN ('INVOICE', 'RECEIPT', 'REMINDER', 'STATUS')),
    CONSTRAINT email_log_status_check CHECK (status IN ('SENT', 'FAILED', 'SKIPPED'))
);
CREATE INDEX email_log_society_idx ON email_log (society_id, created_at DESC);
