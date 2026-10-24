-- Visitor override: a society-wide authority (SECRETARY, and ADMIN by inheritance) may approve or
-- reject on a flat's behalf, e.g. when no resident answers. Recorded so it is never mistaken for
-- the residents' own decision.

INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('VISITOR','APPROVAL',NULL,'OVERRIDE','VISITOR.APPROVAL.OVERRIDE','Approve/reject a visitor on behalf of the flat');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'VISITOR.APPROVAL.OVERRIDE'
WHERE r.code = 'SECRETARY';

ALTER TABLE visitor_entry ADD COLUMN responded_as_override BOOLEAN NOT NULL DEFAULT false;
