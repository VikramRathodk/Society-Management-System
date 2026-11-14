-- Society onboarding (module 7, step O3): residents from a sheet. The uploaded rows are staged here and
-- checked again whenever they are read; sending turns each good row into a resident invitation (straight
-- away in a live society, at go-live while it is being set up). One open sheet per society.
CREATE TABLE resident_import (
    id          BIGSERIAL PRIMARY KEY,
    society_id  BIGINT       NOT NULL REFERENCES society(id) ON DELETE CASCADE,
    file_name   VARCHAR(255),
    status      VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    uploaded_by BIGINT       NOT NULL REFERENCES users(id),
    uploaded_at TIMESTAMP    NOT NULL,
    sent_by     BIGINT REFERENCES users(id),
    sent_at     TIMESTAMP,
    CONSTRAINT resident_import_status_check CHECK (status IN ('OPEN', 'SENT')),
    CONSTRAINT resident_import_sent_check CHECK (status <> 'SENT' OR sent_at IS NOT NULL)
);

CREATE UNIQUE INDEX resident_import_one_open ON resident_import (society_id) WHERE status = 'OPEN';

CREATE TABLE resident_import_row (
    id            BIGSERIAL PRIMARY KEY,
    import_id     BIGINT       NOT NULL REFERENCES resident_import(id) ON DELETE CASCADE,
    -- The line in the file, for messages.
    row_number    INTEGER      NOT NULL,
    -- As written in the sheet.
    wing_text     VARCHAR(100),
    flat_text     VARCHAR(100),
    name          VARCHAR(255),
    phone_text    VARCHAR(50),
    type_text     VARCHAR(50),
    -- As understood (null when it couldn't be).
    flat_id       BIGINT REFERENCES flat(id) ON DELETE SET NULL,
    phone         VARCHAR(15),
    resident_type VARCHAR(10),
    error         VARCHAR(255),
    invitation_id BIGINT REFERENCES invitation(id) ON DELETE SET NULL,
    CONSTRAINT resident_import_row_type_check CHECK (resident_type IS NULL OR resident_type IN ('OWNER', 'FAMILY', 'TENANT'))
);

CREATE INDEX resident_import_row_import_idx ON resident_import_row (import_id, row_number);
