-- Chairman: a built-in society role between SECRETARY and COMMITTEE.
-- New hierarchy: SUPER_ADMIN > ADMIN > SECRETARY > CHAIRMAN > COMMITTEE > RESIDENT, and SECRETARY > GUARD.
-- CHAIRMAN gets everything COMMITTEE has; SECRETARY keeps everything it had, now through CHAIRMAN.
-- No direct grants yet: chairman-only powers (e.g. policy approval) are added by the modules that need them.

INSERT INTO role (code, name, description, scope_level, requires_flat, all_permissions, is_system) VALUES
('CHAIRMAN', 'Chairman', 'Heads the society committee', 'SOCIETY', false, false, true);

DELETE FROM role_inheritance
WHERE role_id = (SELECT id FROM role WHERE code = 'SECRETARY')
  AND inherited_role_id = (SELECT id FROM role WHERE code = 'COMMITTEE');

INSERT INTO role_inheritance (role_id, inherited_role_id)
SELECT r.id, i.id FROM role r JOIN role i ON (r.code, i.code) IN (
    ('SECRETARY', 'CHAIRMAN'),
    ('CHAIRMAN', 'COMMITTEE')
);
