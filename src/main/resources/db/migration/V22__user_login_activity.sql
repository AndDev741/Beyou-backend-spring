-- Product analytics phase 1 (see UserActivityTracker).
--
-- last_login_at: last time a session token was issued for the account — every
--                login path (password, Google web, Google mobile, refresh) funnels
--                through RefreshTokenService.createRefreshToken, which records it.
-- last_seen_at:  last authenticated request, throttled to at most one write per
--                user per 5-minute activity window by UserActivityTracker.
--
-- Both columns are DELIBERATELY unmapped on the User entity and written only by
-- native queries (UserRepository.recordLogin / recordSeen): the entity is loaded
-- by SecurityFilter on every request and saved by unrelated flows, so a mapped
-- field would let a stale in-memory value overwrite a fresher one on save.
ALTER TABLE users ADD COLUMN last_login_at timestamptz;
ALTER TABLE users ADD COLUMN last_seen_at timestamptz;

-- "logins per day/week/month" dashboards filter on this column.
CREATE INDEX idx_users_last_login_at ON users (last_login_at);
