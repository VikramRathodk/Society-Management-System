-- Residents inviting family and tenants onto their own flat. When the society requires it, such an
-- invitation waits (AWAITING_APPROVAL) until a committee member approves it; only then does its link work.
ALTER TABLE invitation
    ADD COLUMN approved_at TIMESTAMP,
    ADD COLUMN approved_by BIGINT REFERENCES users(id);

CREATE INDEX idx_invitation_flat_status ON invitation (flat_id, status);

INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('USER','INVITE',NULL,'APPROVE','USER.INVITE.APPROVE','Approve or reject invitations sent by residents');

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM role r JOIN permission p ON p.code = 'USER.INVITE.APPROVE'
WHERE r.code = 'COMMITTEE';
