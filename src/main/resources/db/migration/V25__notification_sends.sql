-- One row per engagement mail actually sent, so nothing is sent twice and the daily
-- budget can be counted.
--
-- The nudge job runs hourly and walks timezones, so the same account comes back
-- round every hour of every day. Without a record of what already went out, a
-- restart, a backfill, or simply the next hour would mail the same person the same
-- thing again — and the fastest way to teach somebody to mark a sender as spam is
-- to send the same message twice.
--
-- Three separate limits read this table, and they are not the same limit:
--
--   1. Per user, per kind, per day. The unique constraint below, so the database
--      refuses a duplicate even if two passes race. This is the one that must be a
--      constraint rather than a query: a check-then-insert cannot be trusted when
--      the thing it protects is somebody's inbox.
--   2. Per user, across all kinds, a minimum gap in days. Two different triggers can
--      fire on the same account on the same morning, and each is individually
--      justified — the sum is not.
--   3. Global per day, because Brevo's free tier is 300 e-mails/day shared with the
--      transactional mail that people actually asked for. Transactional wins: a
--      password reset that does not arrive because a nudge spent the budget is a
--      much worse failure than a nudge that never goes out.
--
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS notification_sends (
    id uuid NOT NULL,
    user_id uuid NOT NULL,

    -- Which nudge. A varchar mirroring the NudgeKind enum rather than a native enum
    -- type, following V19: adding a value in Java then needs no migration, and the
    -- CHECK below is what stops a typo becoming a row nobody queries for.
    -- squawk-ignore prefer-text-field
    kind varchar(32) NOT NULL,

    -- The OWNER'S local date, not the server's. The whole job exists to act at a
    -- sensible hour where the reader lives, so "already sent today" has to mean their
    -- today. Storing the server's date would let a user in UTC+13 receive the same
    -- nudge twice across one of their days.
    sent_on date NOT NULL,

    created_at timestamptz NOT NULL,

    CONSTRAINT pk_notification_sends PRIMARY KEY (id),
    CONSTRAINT uq_notification_sends_user_kind_day UNIQUE (user_id, kind, sent_on),
    CONSTRAINT ck_notification_sends_kind CHECK (kind IN (
        'XP_RECOVERY_WINDOW',
        'STREAK_RECORD_AT_RISK'
    )),
    -- CASCADE, like notification_preferences (V24): these rows are a log of mail sent
    -- to an account, and they have no meaning once the account is gone. A plain
    -- foreign key here would block the deletion they are attached to.
    CONSTRAINT fk_notification_sends_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

-- Limit 2 above: the newest send for one user, regardless of kind.
CREATE INDEX IF NOT EXISTS notification_sends_user_day_idx
    ON notification_sends(user_id, sent_on DESC);

-- Limit 3 above: how many went out today, across every user. Deliberately its own
-- index rather than relying on the one above, whose leading column is the user.
CREATE INDEX IF NOT EXISTS notification_sends_day_idx
    ON notification_sends(sent_on);

-- A note for whoever adds the third trigger. The CHECK constraint has to grow with
-- the enum: adding a value in Java without adding it here makes every write of the
-- new kind fail, at send time, in a scheduled job nobody is watching. That is the
-- same trap V19's enum columns carry, and the reason the constraint is spelled out
-- here rather than left to the application.
