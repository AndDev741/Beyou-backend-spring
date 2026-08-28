-- The Focus Mode's history: what was actually run, and the small things done alongside it.
--
-- Two tables, and the shape of each is a decision worth stating.
--
-- `focus_cycles` holds ONE ROW PER COMPLETED CYCLE rather than one per sitting. A sitting has no
-- reliable end: the app can be killed, the tab closed, the phone can die, and an "open session" row
-- would then need reconciling forever by something that has to guess. A completed cycle is a fact
-- that never needs closing. "Four pomodoros today" is a count over these rows, which is cheaper to
-- keep correct than a mutable session. Nothing is written for an abandoned cycle: the feature has
-- no failure state, so there is nothing to record.
--
-- `focus_micro_tasks` is scoped to a routine ITEM, not to a sitting. That is the user's own
-- specification and it reverses what shipped in F4: changing item does not carry the list over,
-- unless the micro-task is pinned, in which case selecting another item CREATES a row for that item
-- too. So `pinned` is a template flag, and a pinned "stretch" walked across four items leaves four
-- rows, one per item, each independently tickable. That is also what makes them appear per item in
-- the day's snapshot.
--
-- Both tables carry the USER'S LOCAL DAY, not a server timestamp's date. Every other dated row in
-- this schema does the same (see `entity_check_day`, `routine_snapshot`), because a check placed at
-- 23:30 in Lisbon belongs to that Lisbon day whatever the server thinks.
--
-- SET LOCAL, not SET — see V13/V14/V19/V26. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS focus_cycles (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- The user's own day, resolved from their timezone before the insert.
    cycle_date date NOT NULL,
    -- Nullable on purpose: a cycle can be run with nothing selected (an empty routine, or the whole
    -- routine rather than one item). ON DELETE SET NULL rather than CASCADE, because deleting a
    -- routine must not erase the fact that somebody focused for 25 minutes that morning.
    item_group_id uuid,
    -- A varchar mirroring the CycleKind enum rather than a native enum type, following V19 and
    -- V25: adding a value in Java then needs no migration, and the CHECK below is what stops a
    -- typo becoming a row nobody queries for.
    -- squawk-ignore prefer-text-field
    kind varchar(16) NOT NULL,

    started_at timestamptz NOT NULL,
    ended_at timestamptz NOT NULL,

    -- A duration bounded by the CHECK below at 180. `int` is four orders of magnitude more room
    -- than the constraint allows, so the 32-bit ceiling is not reachable here.
    -- squawk-ignore prefer-bigint-over-int
    minutes integer NOT NULL,
    CONSTRAINT focus_cycles_pkey PRIMARY KEY (id),
    CONSTRAINT focus_cycles_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT focus_cycles_item_group_fkey FOREIGN KEY (item_group_id)
        REFERENCES item_groups (id) ON DELETE SET NULL,
    -- Mirrors the CycleKind enum in Java. Adding a value there without adding it here makes every
    -- write of the new kind fail, at insert time, inside whatever request happened to trigger it.
    CONSTRAINT focus_cycles_kind_check
        CHECK (kind IN ('POMODORO', 'SHORT_BREAK', 'LONG_BREAK')),
    -- The clamp the client already applies, restated where it cannot be bypassed.
    CONSTRAINT focus_cycles_minutes_check CHECK (minutes >= 1 AND minutes <= 180)
);

-- The read path is always "this user, this day", for the history screen and for the snapshot join.
CREATE INDEX IF NOT EXISTS idx_focus_cycles_user_date ON focus_cycles (user_id, cycle_date);

CREATE TABLE IF NOT EXISTS focus_micro_tasks (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    task_date date NOT NULL,
    -- NOT NULL, unlike the cycle's: a micro-task belongs to an item by definition now. CASCADE
    -- here and not SET NULL, because a micro-task with no item is not a thing this model has.
    item_group_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    name varchar(80) NOT NULL,

    pinned boolean DEFAULT false NOT NULL,

    -- A timestamp rather than a boolean, so "done" carries when. Null is open.
    done_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT focus_micro_tasks_pkey PRIMARY KEY (id),
    CONSTRAINT focus_micro_tasks_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT focus_micro_tasks_item_group_fkey FOREIGN KEY (item_group_id)
        REFERENCES item_groups (id) ON DELETE CASCADE,
    CONSTRAINT focus_micro_tasks_name_check CHECK (char_length(trim(name)) BETWEEN 1 AND 80),
    -- One name per item per day. This is what makes materialising a pinned template idempotent:
    -- the client can ask for the list as often as it likes and the second ask creates nothing.
    CONSTRAINT focus_micro_tasks_unique_per_item
        UNIQUE (user_id, task_date, item_group_id, name)
);

-- Two reads, two indexes. The first is the screen asking for one item's list; the second is the
-- snapshot asking for a whole day's, and the pinned-template lookup which scans this user's pinned
-- rows.
CREATE INDEX IF NOT EXISTS idx_focus_micro_tasks_item_date
    ON focus_micro_tasks (user_id, task_date, item_group_id);
CREATE INDEX IF NOT EXISTS idx_focus_micro_tasks_pinned
    ON focus_micro_tasks (user_id, pinned);
