-- The Daily Briefing's cached narrative: at most one row per user per day.
--
-- What this table does NOT hold is the point of it. The briefing's facts — what was
-- missed yesterday, which goals are close, where the streak stands — are recomputed from
-- the live tables on every read, because they are three or four indexed queries and
-- because the user can change them from inside the dialog itself: check a forgotten habit
-- and the counts have to move underneath. Caching those would mean either a stale panel or
-- an invalidation hook on every check path, and both are worse than asking again.
--
-- The narrative is the opposite. It costs an LLM call against a free-tier chain with
-- cooldown windows, it is prose rather than data, and nothing the user does during the day
-- makes yesterday's recap wrong enough to be worth paying for again. So it is written once
-- per day and read from here afterwards.
--
-- Generated on demand, at the first dashboard open of the user's new day, and deliberately
-- NOT in the nightly day-close pass. That pass walks every account that exists; putting a
-- model call in it would spend the quota of people who actually open the app on people who
-- do not. An account that never opens the dialog never creates a row here.
--
-- `briefing_date` is the account's own day, resolved through UserDateResolver like every
-- other date in this schema (R15). Someone in São Paulo opening the app at 00:30 gets that
-- São Paulo day, not the server's.
--
-- ON DELETE CASCADE, matching V31. A briefing is derived data with no meaning past the
-- account that it describes, and it is derived from rows that cascade already — keeping it
-- would leave prose about habits that no longer exist. It also keeps this table out of the
-- hand-deleted list in UserService.deleteUser and out of the manual-delete runbook.
--
-- SET LOCAL, not SET — see V13/V14/V19/V26/V27/V30/V31. Flyway has no datasource of its
-- own, so a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS daily_briefing (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- The user's own day. See the header.
    briefing_date date NOT NULL,
    -- The generated prose, as JSON: two short line lists, one per carousel page. Null
    -- while the model has not answered yet, and null forever on an account whose chain
    -- was unavailable that morning — the clients render translated fallback copy in both
    -- cases, so a null here is a normal state and not an error to repair.
    narrative_json text,
    -- PENDING  — asked for, deadline passed before the model answered. May still become
    --            READY if the call lands afterwards; the row is the only thing that knows.
    -- READY    — narrative_json holds the generated lines.
    -- UNAVAILABLE — the chain refused or failed twice. Not retried for this day: a chain
    --            in cooldown will refuse the retry too, and the panel reads the same with
    --            or without it.
    --
    -- A varchar enum with a CHECK rather than a native enum type, following V19 and V27.
    -- Adding a value in Java without adding it here makes every write of the new kind fail
    -- at insert time, inside a request nobody is watching.
    -- squawk-ignore prefer-text-field
    narrative_status varchar(16) NOT NULL,
    -- When the user actually saw the dialog. Server-side rather than in each client's
    -- local storage on purpose: a person who closes yesterday's loose ends on their phone
    -- and then opens the web would otherwise be asked the same questions twice.
    seen_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT daily_briefing_pkey PRIMARY KEY (id),
    CONSTRAINT daily_briefing_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    -- One briefing per day. The service reads before it writes, but two clients opening
    -- within the same second race past that read, and this is what makes the loser retry
    -- against the winner's row instead of creating a second one and paying for a second
    -- model call.
    CONSTRAINT daily_briefing_user_day_key UNIQUE (user_id, briefing_date),
    CONSTRAINT daily_briefing_status_check
        CHECK (narrative_status IN ('PENDING', 'READY', 'UNAVAILABLE'))
);

-- The retention sweep deletes by age across all users, so it needs the date leading.
-- Every other read is covered by the unique constraint's own index.
CREATE INDEX IF NOT EXISTS idx_daily_briefing_date ON daily_briefing (briefing_date);
