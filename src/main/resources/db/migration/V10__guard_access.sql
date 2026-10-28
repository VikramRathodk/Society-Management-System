-- Guards pick the visitor's flat at the gate, so they need to browse the society's wings and flats.
-- ON CONFLICT: some dev databases already had this grant added by hand.
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'FLAT.DIRECTORY.VIEW'
WHERE r.code = 'GUARD'
ON CONFLICT DO NOTHING;

-- Guards see the visitors they logged plus every still-active entry (PENDING, APPROVED, ENTERED), so the
-- next shift can mark a colleague's visitors entered or exited. The society's full gate history, including
-- finished entries logged by other guards, takes this code: COMMITTEE (and every role above, by inheritance).
INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('VISITOR','HISTORY',NULL,'VIEW','VISITOR.HISTORY.VIEW','View the society''s full visitor history');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'VISITOR.HISTORY.VIEW'
WHERE r.code = 'COMMITTEE';
