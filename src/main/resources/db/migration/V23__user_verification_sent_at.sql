-- When the last verification mail went out, so POST /auth/resend-verification can
-- refuse a second one inside the cooldown.
--
-- The cooldown has to live in the database rather than in memory. It is the only
-- thing stopping a double-tap from replacing the token in the link the user is
-- about to click, and an in-memory counter is per-instance and lost on restart,
-- which are exactly the two moments a retry is most likely.
--
-- Deriving "last sent" from verification_token_expiry minus the TTL was the other
-- option and is rejected: it ties the cooldown to a TTL that is now configurable,
-- so changing EMAIL_VERIFICATION_TTL_HOURS would silently move the cooldown too.
--
-- Nullable, and no backfill. A null reads as "no mail on record", which is the
-- right answer for every row that predates this column: those are the accounts
-- already stranded by the missing resend, and holding a cooldown against them
-- would be holding them to a mail nobody can prove was sent.
--
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- timestamptz, following V22 (last_login_at / last_seen_at) rather than the
-- timestamp-without-zone of verification_token_expiry beside it. The sibling
-- column predates that turn and carries a LocalDateTime read in whatever zone the
-- JVM happens to run in; this one is compared against Instant.now() to decide a
-- cooldown, and an instant is what it means. Adding a nullable column with no
-- default is catalog-only on Postgres 11+: brief ACCESS EXCLUSIVE lock, no table
-- rewrite, and the lock_timeout above is the backstop.
ALTER TABLE users ADD COLUMN IF NOT EXISTS verification_token_sent_at timestamptz;
