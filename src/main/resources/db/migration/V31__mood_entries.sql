-- Mood tracking and journaling: one entry per user per day.
--
-- The unique constraint on (user_id, entry_date) is the whole model. A day has one
-- mood, so "how do I feel today" is an upsert rather than an insert, and the widget
-- can send the same PUT twice without producing two rows. Everything downstream —
-- the week strip, the month calendar, the streak count — reads a range and trusts
-- that one date means one row.
--
-- The day comes from the account's own timezone through UserDateResolver, like every
-- other date in this schema (R15). Someone marking their mood at 23:50 in São Paulo
-- is recording Tuesday, not Wednesday, and this row is permanent history.
--
-- `note` is `text`, not `varchar`. It is free-form journaling, the one place in the
-- product where a person writes for themselves at length, and a length cap that lives
-- in the column is a truncation nobody can undo. The 4000-character limit belongs in
-- the DTO, where exceeding it is a validation error the client can show, not a
-- database error mid-request.
--
-- No XP column and no link to the gamification tables, deliberately. Paying XP for
-- reporting a feeling turns the scale into a chore to be farmed, and the data stops
-- describing anything. The only streak the UI shows is counted client-side from these
-- rows.
--
-- ON DELETE CASCADE: a journal is not history worth keeping past the account that
-- wrote it. This is the most personal data the product stores, so deleting the
-- account has to take it, and the account-deletion test asserts exactly that.
--
-- SET LOCAL, not SET — see V13/V14/V19/V26/V27/V30. Flyway has no datasource of its
-- own, so a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS mood_entries (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- The user's own day, resolved from their timezone before the write.
    entry_date date NOT NULL,
    -- A five-point scale, 1 (awful) to 5 (great). A number rather than a varchar
    -- enum, unlike V19 and V27, because this one is genuinely ordinal: the week
    -- average and the month trend are arithmetic on it. The CHECK below is what a
    -- named enum would have given us, and the labels live in the clients' i18n
    -- files where they can be translated.
    --
    -- `integer` and not `smallint`, even though five values fit in two bytes: the
    -- entity field is an `Integer`, and Hibernate's ddl-auto:validate refuses to boot
    -- on int2-against-INTEGER. Two bytes per row per day is not worth a `Short` in
    -- every DTO and every piece of arithmetic above it.
    -- squawk-ignore prefer-bigint-over-int
    mood integer NOT NULL,
    -- Free text, no length limit in the column. See the header.
    note text,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT mood_entries_pkey PRIMARY KEY (id),
    CONSTRAINT mood_entries_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- One mood per day. MoodService reads this row before writing, but two taps on
    -- the widget half a second apart race past that check, and this is what makes
    -- the loser retry against the winner's row instead of creating a second one.
    CONSTRAINT mood_entries_user_day_key UNIQUE (user_id, entry_date),
    -- Mirrors the 1..5 range the DTO validates. Widening the scale in Java without
    -- widening it here makes every write of the new value fail at insert time.
    CONSTRAINT mood_entries_mood_check CHECK (mood BETWEEN 1 AND 5)
);

-- Every read is "this user, this range of days", newest first for the entry list.
CREATE INDEX IF NOT EXISTS idx_mood_entries_user_date ON mood_entries (user_id, entry_date DESC);
