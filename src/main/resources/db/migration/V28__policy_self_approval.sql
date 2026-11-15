-- Rules & policies: SECRETARY and above (ADMIN inherits it; SUPER_ADMIN holds every permission) may
-- approve a policy they drafted themselves, straight from the draft. The CHAIRMAN and other approvers
-- still need another approver, unless the society turns on `policySelfApprovalAllowed`.
INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('POLICY','RULE',NULL,'SELF_APPROVE','POLICY.RULE.SELF_APPROVE','Approve and publish a policy one drafted oneself');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'POLICY.RULE.SELF_APPROVE' WHERE r.code = 'SECRETARY';
