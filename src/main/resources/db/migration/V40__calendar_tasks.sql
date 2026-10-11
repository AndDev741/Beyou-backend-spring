-- Calendar tasks: one-off obligations with a date, an optional time and a reminder.
--
-- A table of their own rather than a date column on `tasks`. A routine task completes only
-- through a routine check, pays streak-aware XP and deletes itself the day after a one-time
-- check. A calendar task does none of that: it completes on its own, pays a flat amount,
-- and stays on its day forever, done or not. Folding the two together would bend all three
-- rules for every routine task to serve this one.
--
-- No stored reminder instant. The instant is the task's date at its time (or at the
-- owner's default reminder time when it has none), minus the advance notice, in the owner's
-- timezone, and CalendarReminderTime computes it on every read. A stored column would go
-- stale the moment the owner changed timezone or default time, and nothing would notice.
--
-- What a reminder needs to remember is whether anyone reacted to it, and that is two
-- timestamps: `acknowledged_at` (the browser notification was clicked) and
-- `reminder_email_sent_at` (the fallback mail went out). Editing the date, time or advance
-- clears both, so a moved task reminds again.
--
-- ON DELETE CASCADE, like mood_entries (V31): a to-do list has no meaning past the account
-- that wrote it, and a plain foreign key would block the account deletion. The deletion
-- tests seed a row here and assert it is gone.
--
-- SET LOCAL, not SET — see V13/V14/V19/V26/V27/V31. Flyway has no datasource of its own, so
-- a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS calendar_tasks (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    title varchar(255) NOT NULL,
    -- A short note, not a journal: the mood diary is where long writing goes. The cap is in
    -- the column so a client that skips the DTO cannot store a novel in a reminder mail.
    -- squawk-ignore prefer-text-field
    note varchar(1000),
    -- The owner's own day, like every other date in this schema.
    task_date date NOT NULL,
    -- Null means "some time that day": the reminder then uses the owner's default time.
    task_time time(0) without time zone,
    -- Minutes before the task's time the reminder fires. `integer` and not `bigint` because
    -- the entity field is an int and validate refuses int8-against-INTEGER; the CHECK caps it
    -- at a week, mirroring the DTO.
    -- squawk-ignore prefer-bigint-over-int
    advance_minutes integer NOT NULL DEFAULT 0,
    completed_at timestamptz,
    -- Exactly what completing paid, so un-completing takes back that amount and not whatever
    -- the formula says on the day it is undone.
    xp_awarded double precision NOT NULL DEFAULT 0,
    acknowledged_at timestamptz,
    reminder_email_sent_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT calendar_tasks_pkey PRIMARY KEY (id),
    CONSTRAINT calendar_tasks_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- Mirrors CalendarTask.MAX_ADVANCE_MINUTES. Widening it in Java without widening it here
    -- makes every write of the new value fail at insert time.
    CONSTRAINT calendar_tasks_advance_check CHECK (advance_minutes BETWEEN 0 AND 10080)
);

-- Every read the clients make is "this user, this range of days".
CREATE INDEX IF NOT EXISTS idx_calendar_tasks_user_date ON calendar_tasks (user_id, task_date);

-- The reminder mail job, every five minutes, across every account: the tasks nobody has
-- reacted to yet, in a window of days. Partial, so done and answered tasks, which are most
-- of the table after a while, cost the job nothing.
CREATE INDEX IF NOT EXISTS idx_calendar_tasks_reminder_pending ON calendar_tasks (task_date)
    WHERE completed_at IS NULL AND acknowledged_at IS NULL AND reminder_email_sent_at IS NULL;

-- The same job's daily cap counts the mails sent since midnight UTC. Partial for the
-- opposite reason: only mailed tasks carry the timestamp.
CREATE INDEX IF NOT EXISTS idx_calendar_tasks_reminder_sent ON calendar_tasks (reminder_email_sent_at)
    WHERE reminder_email_sent_at IS NOT NULL;

-- Reminder preferences, beside the engagement switch from V24 and independent of it.
-- Turning off nudges nobody asked for must not silence reminders somebody set themselves,
-- so these are separate columns rather than a reuse of engagement_email.
--
-- All three default on (and 09:00), which is also what an account with no row reads as:
-- NotificationPreferencesService treats a missing row as the defaults, so the defaults here
-- and there have to agree. A constant DEFAULT on ADD COLUMN is metadata-only from
-- PostgreSQL 11 onwards, so no rewrite of the table.
ALTER TABLE notification_preferences
    ADD COLUMN IF NOT EXISTS reminder_email boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS reminder_web boolean NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS reminder_default_time time(0) without time zone NOT NULL DEFAULT '09:00';
