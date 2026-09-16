-- Permission catalog
INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
-- USER
('USER','PROFILE',NULL,'VIEW','USER.PROFILE.VIEW','View own/other profile'),
('USER','PROFILE',NULL,'UPDATE','USER.PROFILE.UPDATE','Edit own profile'),
('USER','ACCOUNT',NULL,'CREATE','USER.ACCOUNT.CREATE','Create a new user account'),
('USER','ACCOUNT',NULL,'VIEW','USER.ACCOUNT.VIEW','View all user accounts'),
('USER','ACCOUNT',NULL,'UPDATE','USER.ACCOUNT.UPDATE','Edit another user account'),
('USER','ACCOUNT',NULL,'DELETE','USER.ACCOUNT.DELETE','Deactivate/remove a user'),
-- FLAT
('FLAT','DIRECTORY',NULL,'VIEW','FLAT.DIRECTORY.VIEW','View flat/resident directory'),
('FLAT','STRUCTURE',NULL,'CREATE','FLAT.STRUCTURE.CREATE','Add wing/flat'),
('FLAT','STRUCTURE',NULL,'UPDATE','FLAT.STRUCTURE.UPDATE','Edit wing/flat'),
('FLAT','STRUCTURE',NULL,'DELETE','FLAT.STRUCTURE.DELETE','Remove wing/flat'),
-- VISITOR
('VISITOR','ENTRY',NULL,'CREATE','VISITOR.ENTRY.CREATE','Log a visitor entry'),
('VISITOR','ENTRY',NULL,'VIEW','VISITOR.ENTRY.VIEW','View visitor logs'),
('VISITOR','ENTRY',NULL,'UPDATE','VISITOR.ENTRY.UPDATE','Edit/mark entered-exited'),
('VISITOR','ENTRY',NULL,'DELETE','VISITOR.ENTRY.DELETE','Remove a visitor log'),
('VISITOR','ENTRY',NULL,'EXPORT','VISITOR.ENTRY.EXPORT','Export visitor log report'),
('VISITOR','APPROVAL',NULL,'APPROVE','VISITOR.APPROVAL.APPROVE','Approve visitor request'),
('VISITOR','APPROVAL',NULL,'REJECT','VISITOR.APPROVAL.REJECT','Reject visitor request'),
-- NOTICE
('NOTICE','POST',NULL,'CREATE','NOTICE.POST.CREATE','Post a notice'),
('NOTICE','POST',NULL,'VIEW','NOTICE.POST.VIEW','View notices'),
('NOTICE','POST',NULL,'UPDATE','NOTICE.POST.UPDATE','Edit a notice'),
('NOTICE','POST',NULL,'DELETE','NOTICE.POST.DELETE','Delete a notice'),
-- COMPLAINT
('COMPLAINT','TICKET',NULL,'CREATE','COMPLAINT.TICKET.CREATE','Raise a complaint'),
('COMPLAINT','TICKET',NULL,'VIEW','COMPLAINT.TICKET.VIEW','View complaints'),
('COMPLAINT','TICKET',NULL,'UPDATE','COMPLAINT.TICKET.UPDATE','Edit a complaint'),
('COMPLAINT','TICKET',NULL,'DELETE','COMPLAINT.TICKET.DELETE','Withdraw a complaint'),
('COMPLAINT','TICKET',NULL,'EXPORT','COMPLAINT.TICKET.EXPORT','Export complaint report'),
('COMPLAINT','RESOLUTION',NULL,'ASSIGN','COMPLAINT.RESOLUTION.ASSIGN','Assign complaint to staff'),
('COMPLAINT','RESOLUTION',NULL,'APPROVE','COMPLAINT.RESOLUTION.APPROVE','Mark complaint resolved'),
('COMPLAINT','RESOLUTION',NULL,'REJECT','COMPLAINT.RESOLUTION.REJECT','Reopen/reject resolution'),
-- SOCIETY
('SOCIETY','SETTINGS',NULL,'VIEW','SOCIETY.SETTINGS.VIEW','View society profile'),
('SOCIETY','SETTINGS',NULL,'UPDATE','SOCIETY.SETTINGS.UPDATE','Edit society profile'),
-- COMPLEX (reserved — no role assigned until a Complex Admin role or shared-scope feature is confirmed)
('COMPLEX','SETTINGS',NULL,'VIEW','COMPLEX.SETTINGS.VIEW','View complex profile'),
('COMPLEX','SETTINGS',NULL,'UPDATE','COMPLEX.SETTINGS.UPDATE','Edit complex profile'),
-- PARKING (reserved — workflow deferred to Phase 2)
('PARKING','SLOT',NULL,'CREATE','PARKING.SLOT.CREATE','Add a parking slot'),
('PARKING','SLOT',NULL,'VIEW','PARKING.SLOT.VIEW','View parking slots'),
('PARKING','SLOT',NULL,'UPDATE','PARKING.SLOT.UPDATE','Edit a parking slot'),
('PARKING','SLOT',NULL,'DELETE','PARKING.SLOT.DELETE','Remove a parking slot'),
('PARKING','SLOT',NULL,'ASSIGN','PARKING.SLOT.ASSIGN','Assign a slot to a flat'),
-- STAFF (reserved — workflow deferred to Phase 2)
('STAFF','MEMBER',NULL,'CREATE','STAFF.MEMBER.CREATE','Register a staff member'),
('STAFF','MEMBER',NULL,'VIEW','STAFF.MEMBER.VIEW','View staff members'),
('STAFF','MEMBER',NULL,'UPDATE','STAFF.MEMBER.UPDATE','Edit staff member details'),
('STAFF','MEMBER',NULL,'DELETE','STAFF.MEMBER.DELETE','Remove a staff member'),
-- SYSTEM (meta-permission — gates who can edit the permission system itself)
('SYSTEM','PERMISSION',NULL,'VIEW','SYSTEM.PERMISSION.VIEW','View role-permission mappings'),
('SYSTEM','PERMISSION',NULL,'UPDATE','SYSTEM.PERMISSION.UPDATE','Edit role-permission mappings');

-- Role -> permission mappings (per the finalized matrix)
-- COMPLEX.*, PARKING.*, STAFF.*, COMPLAINT.RESOLUTION.ASSIGN are seeded above but intentionally left unmapped to any role.

INSERT INTO role_permission (role, permission_id)
SELECT 'RESIDENT', id FROM permission WHERE code IN (
                                                     'USER.PROFILE.VIEW','USER.PROFILE.UPDATE',
                                                     'FLAT.DIRECTORY.VIEW',
                                                     'VISITOR.ENTRY.VIEW','VISITOR.APPROVAL.APPROVE','VISITOR.APPROVAL.REJECT',
                                                     'NOTICE.POST.VIEW',
                                                     'COMPLAINT.TICKET.CREATE','COMPLAINT.TICKET.VIEW','COMPLAINT.TICKET.UPDATE','COMPLAINT.TICKET.DELETE'
    );

INSERT INTO role_permission (role, permission_id)
SELECT 'GUARD', id FROM permission WHERE code IN (
                                                  'VISITOR.ENTRY.CREATE','VISITOR.ENTRY.VIEW','VISITOR.ENTRY.UPDATE',
                                                  'NOTICE.POST.VIEW'
    );

INSERT INTO role_permission (role, permission_id)
SELECT 'COMMITTEE', id FROM permission WHERE code IN (
                                                      'USER.PROFILE.VIEW','USER.ACCOUNT.VIEW',
                                                      'FLAT.DIRECTORY.VIEW',
                                                      'VISITOR.ENTRY.VIEW','VISITOR.ENTRY.EXPORT',
                                                      'NOTICE.POST.CREATE','NOTICE.POST.VIEW','NOTICE.POST.UPDATE','NOTICE.POST.DELETE',
                                                      'COMPLAINT.TICKET.VIEW','COMPLAINT.TICKET.EXPORT','COMPLAINT.RESOLUTION.APPROVE','COMPLAINT.RESOLUTION.REJECT'
    );

INSERT INTO role_permission (role, permission_id)
SELECT 'ADMIN', id FROM permission WHERE code IN (
                                                  'USER.PROFILE.VIEW','USER.PROFILE.UPDATE',
                                                  'USER.ACCOUNT.CREATE','USER.ACCOUNT.VIEW','USER.ACCOUNT.UPDATE','USER.ACCOUNT.DELETE',
                                                  'FLAT.DIRECTORY.VIEW','FLAT.STRUCTURE.CREATE','FLAT.STRUCTURE.UPDATE','FLAT.STRUCTURE.DELETE',
                                                  'VISITOR.ENTRY.VIEW','VISITOR.ENTRY.DELETE','VISITOR.ENTRY.EXPORT',
                                                  'NOTICE.POST.CREATE','NOTICE.POST.VIEW','NOTICE.POST.UPDATE','NOTICE.POST.DELETE',
                                                  'COMPLAINT.TICKET.VIEW','COMPLAINT.TICKET.EXPORT','COMPLAINT.RESOLUTION.APPROVE','COMPLAINT.RESOLUTION.REJECT',
                                                  'SOCIETY.SETTINGS.VIEW','SOCIETY.SETTINGS.UPDATE',
                                                  'SYSTEM.PERMISSION.VIEW','SYSTEM.PERMISSION.UPDATE'
    );