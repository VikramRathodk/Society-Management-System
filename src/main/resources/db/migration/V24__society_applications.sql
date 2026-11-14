-- Society onboarding (module 7, step O2): a secretary registers their society from the public site. The
-- application waits for the super admin, who calls the applicant to confirm; approving it creates the
-- society (in SETUP, source SELF_SIGNUP), the applicant's account and their SECRETARY role.
CREATE TABLE society_application (
    id                BIGSERIAL PRIMARY KEY,
    society_name      VARCHAR(255) NOT NULL,
    city              VARCHAR(100) NOT NULL,
    address           VARCHAR(500),
    approx_flats      INTEGER,
    applicant_name    VARCHAR(255) NOT NULL,
    applicant_phone   VARCHAR(15)  NOT NULL,
    applicant_email   VARCHAR(255),
    -- Null when a signed-in user applied with their existing account.
    password_hash     VARCHAR(255),
    applicant_user_id BIGINT REFERENCES users(id),
    message           VARCHAR(1000),
    status            VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMP    NOT NULL,
    created_ip        VARCHAR(45),
    reviewed_at       TIMESTAMP,
    reviewed_by       BIGINT REFERENCES users(id),
    reason            VARCHAR(500),
    society_id        BIGINT REFERENCES society(id),
    CONSTRAINT society_application_status_check CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT society_application_flats_check CHECK (approx_flats IS NULL OR approx_flats BETWEEN 1 AND 10000),
    -- Once decided the password hash is dropped (an approved applicant has an account by then).
    CONSTRAINT society_application_account_check CHECK (status <> 'PENDING' OR password_hash IS NOT NULL OR applicant_user_id IS NOT NULL),
    CONSTRAINT society_application_approved_check CHECK (status <> 'APPROVED' OR society_id IS NOT NULL),
    CONSTRAINT society_application_rejected_check CHECK (status <> 'REJECTED' OR reason IS NOT NULL)
);

-- One pending application per phone number.
CREATE UNIQUE INDEX society_application_one_pending ON society_application (applicant_phone) WHERE status = 'PENDING';
CREATE INDEX society_application_status_idx ON society_application (status, created_at);
CREATE INDEX society_application_ip_idx ON society_application (created_ip, created_at);

-- Platform-level: only SUPER_ADMIN (all permissions) holds these.
INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('SOCIETY','APPLICATION',NULL,'VIEW','SOCIETY.APPLICATION.VIEW','See society registrations from the public site'),
('SOCIETY','APPLICATION',NULL,'UPDATE','SOCIETY.APPLICATION.UPDATE','Approve or reject society registrations');
