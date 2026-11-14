-- Society onboarding (module 7, step O1): a society starts in SETUP and goes LIVE when its secretary
-- has finished the setup wizard. While in SETUP nobody can hold a flat role (residents join at go-live),
-- so residents can't sign in or be notified yet. Existing societies are LIVE.
ALTER TABLE society
    ADD COLUMN status              VARCHAR(10)  NOT NULL DEFAULT 'LIVE',
    ADD COLUMN source              VARCHAR(20)  NOT NULL DEFAULT 'PLATFORM',
    ADD COLUMN went_live_at        TIMESTAMP,
    ADD COLUMN went_live_by        BIGINT REFERENCES users(id),
    -- The profile. `address` (already there) is the street address.
    ADD COLUMN registration_number VARCHAR(50),
    ADD COLUMN city                VARCHAR(100),
    ADD COLUMN state               VARCHAR(100),
    ADD COLUMN pincode             VARCHAR(6),
    ADD COLUMN contact_phone       VARCHAR(15),
    ADD COLUMN contact_email       VARCHAR(255),
    ADD COLUMN logo_key            VARCHAR(100),
    ADD COLUMN notes               VARCHAR(2000),
    ADD CONSTRAINT society_status_check CHECK (status IN ('SETUP', 'LIVE')),
    ADD CONSTRAINT society_source_check CHECK (source IN ('PLATFORM', 'SELF_SIGNUP')),
    ADD CONSTRAINT society_pincode_check CHECK (pincode IS NULL OR pincode ~ '^[1-9][0-9]{5}$');
