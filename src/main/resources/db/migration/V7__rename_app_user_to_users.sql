-- Generic name for the identity table (entity: User). "user" itself is a reserved word in Postgres, so the
-- table is "users". Foreign keys follow the rename automatically; the auto-generated names are renamed too
-- so they stay recognisable.

ALTER TABLE app_user RENAME TO users;

ALTER TABLE users RENAME CONSTRAINT app_user_pkey TO users_pkey;
ALTER TABLE users RENAME CONSTRAINT app_user_phone_key TO users_phone_key;
ALTER INDEX idx_app_user_phone RENAME TO idx_users_phone;
ALTER SEQUENCE app_user_id_seq RENAME TO users_id_seq;
