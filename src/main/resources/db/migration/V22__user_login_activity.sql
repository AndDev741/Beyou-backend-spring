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
--
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Nullable on purpose: an account that has never logged in since this shipped
-- has no value to invent. Adding a nullable column is a catalog-only change —
-- brief ACCESS EXCLUSIVE lock, no table rewrite; lock_timeout above is the
-- backstop if it ever queues behind live traffic.
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_at timestamptz;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at timestamptz;

-- "logins per day/week/month" dashboards filter on this column. Plain CREATE
-- INDEX (not CONCURRENTLY) is the same pre-production call V2 documents: every
-- environment migrates small tables, and CONCURRENTLY cannot run inside
-- Flyway's transaction anyway.
CREATE INDEX IF NOT EXISTS idx_users_last_login_at ON users (last_login_at);
