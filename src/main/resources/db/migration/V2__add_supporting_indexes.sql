-- Supporting indexes for high-traffic query paths, derived from the actual
-- repository queries (not recalled from memory). Table names verified against
-- V1__baseline.sql. IF NOT EXISTS keeps re-runs against partially-migrated
-- environments safe.
--
-- Note: plain CREATE INDEX (not CONCURRENTLY) is deliberate pre-production —
-- every environment migrates empty/small databases. Revisit before the first
-- migration that adds an index to a large production table.

-- User-scoped bulk reads (every dashboard load: findAllByUserId per domain)
CREATE INDEX IF NOT EXISTS idx_habits_user_id         ON habits (user_id);
CREATE INDEX IF NOT EXISTS idx_tasks_user_id          ON tasks (user_id);
CREATE INDEX IF NOT EXISTS idx_goals_user_id          ON goals (user_id);
CREATE INDEX IF NOT EXISTS idx_categories_user_id     ON categories (user_id);
CREATE INDEX IF NOT EXISTS idx_routines_user_id       ON routines (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- Note: no index on refresh_tokens(token_hash) on purpose. The refresh flow
-- resolves tokens by primary key ({UUID}.{rawToken} format -> findById) and
-- token_hash is a salted BCrypt string — unusable as a lookup key by
-- construction. A findByTokenHash repository method existed but had zero
-- callers; it was removed instead of indexed.

-- Latest-per-user password reset token + expiry cleanup
CREATE INDEX IF NOT EXISTS idx_password_reset_user_created
    ON password_reset_tokens (user_id, created_at DESC);

-- Email verification lookup — partial: most users are verified (token NULL)
CREATE INDEX IF NOT EXISTS idx_users_verification_token
    ON users (verification_token)
    WHERE verification_token IS NOT NULL;

-- Schedule → routine lookup (DiaryRoutineRepository.findByScheduleId)
CREATE INDEX IF NOT EXISTS idx_routines_schedule_id ON routines (schedule_id);

-- Timezone-batched work: RoutineSnapshotScheduler queries users by timezone
-- today; the planned notification system will lean on this harder.
CREATE INDEX IF NOT EXISTS idx_users_timezone ON users (timezone);
