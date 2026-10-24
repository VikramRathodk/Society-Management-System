-- Deleting a society or complex is platform-level. These permissions are granted to no role, so only
-- SUPER_ADMIN (all_permissions) holds them. A delete is refused while history references the record
-- (visitors, notices, complaints, staff, parking; a complex's societies) — see SocietyService/ComplexService.

INSERT INTO permission (module, feature, subfeature, action, code, description) VALUES
('SOCIETY','SETTINGS',NULL,'DELETE','SOCIETY.SETTINGS.DELETE','Delete a society with no history'),
('COMPLEX','SETTINGS',NULL,'DELETE','COMPLEX.SETTINGS.DELETE','Delete a complex with no societies');
