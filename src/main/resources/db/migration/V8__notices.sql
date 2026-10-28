-- Notices (M4). The notice table has existed since V1 with nothing writing to it; tighten it now that
-- it is used, and record edits — a notice may be changed by someone other than its author.

ALTER TABLE notice
    ALTER COLUMN society_id SET NOT NULL,
    ALTER COLUMN posted_by SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE notice
    ADD COLUMN updated_at TIMESTAMP,
    ADD COLUMN updated_by BIGINT REFERENCES users(id);

-- The society's notice board, newest first.
CREATE INDEX idx_notice_society_created ON notice(society_id, created_at DESC);

-- COMMITTEE edits/deletes only its own notices (NOTICE.POST.UPDATE/DELETE + an ownership check).
-- Acting on anyone's notice takes this code, granted to SECRETARY (ADMIN inherits, SUPER_ADMIN has all).
INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('NOTICE','POST',NULL,'MODERATE','NOTICE.POST.MODERATE','Edit or delete any notice of the society');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'NOTICE.POST.MODERATE'
WHERE r.code = 'SECRETARY';
