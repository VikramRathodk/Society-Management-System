-- Local dev test data. NOT a Flyway migration: run it by hand against an empty, freshly migrated database
-- (start the app once so Flyway creates the schema, then:
--   psql -h localhost -U postgres -d society_management_db -f scripts/dev-seed.sql).
-- Password hashes are bcrypt ($2a$10$...), which BCryptPasswordEncoder accepts.
-- Every seeded user's password is Pass@123. Stan Lee 9000000999 is SUPER_ADMIN, Nick Fury 9000000616 is SECRETARY.

BEGIN;

INSERT INTO society (name, address) VALUES
('Avengers Tower', '200 Park Avenue, Manhattan, New York'),
('Test Society', 'Dev seed');

INSERT INTO wing (society_id, name)
SELECT id, 'Stark Wing' FROM society WHERE name = 'Avengers Tower'
UNION ALL
SELECT id, 'A' FROM society WHERE name = 'Test Society';

INSERT INTO flat (wing_id, flat_number)
SELECT w.id, 'Penthouse' FROM wing w JOIN society s ON s.id = w.society_id WHERE s.name = 'Avengers Tower'
UNION ALL
SELECT w.id, '1001' FROM wing w JOIN society s ON s.id = w.society_id WHERE s.name = 'Avengers Tower'
UNION ALL
SELECT w.id, '101' FROM wing w JOIN society s ON s.id = w.society_id WHERE s.name = 'Test Society';

INSERT INTO users (name, phone, email, password_hash) VALUES
('Stan Lee',         '9000000999', NULL,                        '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Nick Fury',        '9000000616', 'fury@shield.gov',           '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Steve Rogers',     '9000000001', 'cap@avengers.com',          '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Happy Hogan',      '9000000002', 'happy@starkindustries.com', '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Tony Stark',       '9000000003', 'tony@starkindustries.com',  '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Peter Parker',     '9000000004', 'peter@dailybugle.com',      '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Bruce Banner',     '9000000005', 'bruce@culver.edu',          '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Natasha Romanoff', '9000000006', 'natasha@shield.gov',        '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Wanda Maximoff',   '9000000007', 'wanda@avengers.com',        '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Vision',           '9000000008', 'vision@avengers.com',       '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Dev Admin',        '9999999999', NULL,                        '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2'),
('Test Resident',    '8888888888', NULL,                        '$2a$10$YiYBOVodjNVV1Z3jZkfRjehxZ2IhkIzfWpuVWD2.lc5JHZuQCX3t2');

-- (phone, role, society, flat number, resident type); society/flat NULL where the role has no such scope.
INSERT INTO user_role (user_id, role_id, society_id, flat_id, resident_type)
SELECT u.id, r.id, s.id,
       (SELECT f.id FROM flat f JOIN wing w ON w.id = f.wing_id
        WHERE w.society_id = s.id AND f.flat_number = a.flat_number),
       a.resident_type
FROM (VALUES
    ('9000000999', 'SUPER_ADMIN', NULL,             NULL,        NULL),
    ('9000000616', 'SECRETARY',   'Avengers Tower', NULL,        NULL),
    ('9000000001', 'COMMITTEE',   'Avengers Tower', NULL,        NULL),
    ('9000000002', 'GUARD',       'Avengers Tower', NULL,        NULL),
    ('9000000003', 'RESIDENT',    'Avengers Tower', 'Penthouse', 'OWNER'),
    ('9000000004', 'RESIDENT',    'Avengers Tower', 'Penthouse', 'FAMILY'),
    ('9000000005', 'RESIDENT',    'Avengers Tower', 'Penthouse', 'TENANT'),
    ('9000000006', 'RESIDENT',    'Avengers Tower', 'Penthouse', 'TENANT'),
    ('9000000007', 'RESIDENT',    'Avengers Tower', '1001',      'OWNER'),
    ('9000000008', 'RESIDENT',    'Avengers Tower', '1001',      'FAMILY'),
    ('9999999999', 'SECRETARY',   'Test Society',   NULL,        NULL),
    ('8888888888', 'RESIDENT',    'Test Society',   '101',       'OWNER')
) AS a(phone, role_code, society_name, flat_number, resident_type)
JOIN users u ON u.phone = a.phone
JOIN role r ON r.code = a.role_code
LEFT JOIN society s ON s.name = a.society_name;

COMMIT;
