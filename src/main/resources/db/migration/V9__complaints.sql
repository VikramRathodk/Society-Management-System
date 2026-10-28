-- Complaints (M5). The complaint table has existed since V1 with nothing writing to it; tighten it now that
-- it is used. A complaint belongs to a flat, like a visitor entry: residents see their own flat's complaints,
-- society-wide roles (COMMITTEE and above) see all of them.

ALTER TABLE complaint
    ADD COLUMN flat_id BIGINT REFERENCES flat(id);

ALTER TABLE complaint
    ALTER COLUMN society_id SET NOT NULL,
    ALTER COLUMN flat_id SET NOT NULL,
    ALTER COLUMN raised_by SET NOT NULL,
    ALTER COLUMN category SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL,
    -- Set on the first edit or status change, like notice.updated_at.
    ALTER COLUMN updated_at DROP DEFAULT;

-- A resident's view: their flat's complaints, newest first.
CREATE INDEX idx_complaint_flat_created ON complaint(flat_id, created_at DESC);
