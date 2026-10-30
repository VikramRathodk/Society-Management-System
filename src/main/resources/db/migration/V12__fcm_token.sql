-- FCM tokens are ~160 chars today but Google sets no maximum; widen so a longer one never fails
-- the profile update. The partial index serves the token look-ups (moving a token between users,
-- clearing tokens FCM reports as dead).
ALTER TABLE users ALTER COLUMN fcm_token TYPE VARCHAR(512);
CREATE INDEX idx_users_fcm_token ON users (fcm_token) WHERE fcm_token IS NOT NULL;
