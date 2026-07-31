-- The V1 baseline pinned users.user_role to the single value 'USER'. The ADMIN
-- role added alongside the /feedback/admin/** gate is granted exclusively by a
-- manual database UPDATE — but that UPDATE is rejected by this constraint, so
-- no admin could exist and no admin-only path (including serving a feedback
-- attachment to a triaging admin) was reachable.
--
-- Widen the allowed set to the two values UserRole actually has. NULL still
-- passes, as it did before: rows predating the column default to USER in code.
-- Granting admin stays a manual UPDATE — this migration promotes nobody.
--
-- Squawk ignores are per-line (attach to the next line). The ACCESS EXCLUSIVE
-- lock is instant on this pre-production table (same rationale as V2/V7/V8).

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_user_role_check;

-- squawk-ignore constraint-missing-not-valid
ALTER TABLE users ADD CONSTRAINT users_user_role_check CHECK (user_role IN ('USER', 'ADMIN'));
