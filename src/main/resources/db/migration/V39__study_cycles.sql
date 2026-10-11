-- Study cycles (ciclo de estudos): an ordered queue of study blocks with no fixed weekday.
--
-- A cycle holds notebook topics with a weight each, a week (minutes per weekday) and a list of
-- blocks the planner laid out from those (StudyCyclePlanner). Each block is either TIME ("70
-- minutes of this topic") or GOAL ("understand this page"). Studying moves through the blocks
-- in order; when every block is closed the lap (volta) is complete and the queue starts again.
--
-- Progress is kept per lap, in study_cycle_lap_blocks, one row per block per lap created the
-- first time the lap touches that block. The block itself is configuration and never carries
-- progress, so re-planning a cycle can rewrite the blocks without touching what was studied,
-- and a closed lap is a row in study_cycle_laps whose numbers no later edit can change.
--
-- Minutes come from two places only: a finished pomodoro run on a page of the topic (focus
-- cycles already store the page) and a manual session (study_sessions) for study done outside
-- the app. StudyCycleService.credit is the one method that turns either into lap progress.
--
-- A topic sits in at most one ACTIVE cycle. The service checks it and names the cycle in the
-- refusal; the partial unique index below is what makes it true when two requests race.
--
-- Small integers stay `integer`, not `smallint`, for the reason V31 gives: Hibernate's
-- ddl-auto:validate refuses int2 against an int field.
--
-- Everything cascades from users, directly (study_cycles, study_sessions) or through
-- study_cycles, so deleting an account takes it all. The account-deletion tests seed a row in
-- each table that points at users and check it is gone.
--
-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V30-V34. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS study_cycles (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    name varchar(80) NOT NULL,
    -- The mode new blocks get. Mirrors BlockMode: adding a value in Java without adding it
    -- here makes every write of the new mode fail at insert time.
    -- squawk-ignore prefer-text-field
    default_mode varchar(8) DEFAULT 'GOAL' NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    max_block_minutes integer DEFAULT 90 NOT NULL,
    -- The lap in progress. Closed laps are rows in study_cycle_laps.
    -- squawk-ignore prefer-bigint-over-int
    lap_number integer DEFAULT 1 NOT NULL,
    lap_started_at timestamptz NOT NULL,
    -- Display order among the user's cycles.
    -- squawk-ignore prefer-bigint-over-int
    position integer DEFAULT 0 NOT NULL,
    -- Archived cycles keep their history and free their topics. No unarchive.
    archived_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT study_cycles_pkey PRIMARY KEY (id),
    CONSTRAINT study_cycles_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT study_cycles_default_mode_check CHECK (default_mode IN ('TIME', 'GOAL')),
    CONSTRAINT study_cycles_max_block_check CHECK (max_block_minutes BETWEEN 15 AND 240),
    CONSTRAINT study_cycles_lap_number_check CHECK (lap_number >= 1)
);

CREATE INDEX IF NOT EXISTS idx_study_cycles_user ON study_cycles (user_id);

-- The week: all seven rows always exist, so a rest day is a row with 0 minutes rather than a
-- missing row, and "how much this week" is a plain sum.
CREATE TABLE IF NOT EXISTS study_cycle_days (
    cycle_id uuid NOT NULL,
    -- Mirrors java.time.DayOfWeek. A value outside these seven makes the write fail.
    -- squawk-ignore prefer-text-field
    weekday varchar(10) NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    minutes integer DEFAULT 0 NOT NULL,
    -- Optional. With it the day's slice gets clock times; without it the slice is just an order.
    start_time time,
    CONSTRAINT study_cycle_days_pkey PRIMARY KEY (cycle_id, weekday),
    CONSTRAINT study_cycle_days_cycle_fkey FOREIGN KEY (cycle_id)
        REFERENCES study_cycles (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_days_weekday_check CHECK (weekday IN
        ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT study_cycle_days_minutes_check CHECK (minutes BETWEEN 0 AND 720)
);

CREATE TABLE IF NOT EXISTS study_cycle_topics (
    id uuid NOT NULL,
    cycle_id uuid NOT NULL,
    -- A notebook TOPIC page. Deleting the topic takes it out of the cycle.
    topic_id uuid NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    importance integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    mastery integer NOT NULL,
    -- The person's own minutes per lap for this topic, or null for the planner's suggestion.
    -- squawk-ignore prefer-bigint-over-int
    minutes_override integer,
    -- False once the cycle is archived: the topic is free to join another cycle.
    active boolean DEFAULT true NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    position integer DEFAULT 0 NOT NULL,
    CONSTRAINT study_cycle_topics_pkey PRIMARY KEY (id),
    CONSTRAINT study_cycle_topics_cycle_fkey FOREIGN KEY (cycle_id)
        REFERENCES study_cycles (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_topics_topic_fkey FOREIGN KEY (topic_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_topics_importance_check CHECK (importance BETWEEN 1 AND 5),
    CONSTRAINT study_cycle_topics_mastery_check CHECK (mastery BETWEEN 1 AND 5),
    CONSTRAINT study_cycle_topics_override_check CHECK (minutes_override BETWEEN 5 AND 600),
    CONSTRAINT study_cycle_topics_once UNIQUE (cycle_id, topic_id)
);

-- One active cycle per topic, guaranteed by the database. StudyCycleService checks first so it
-- can say which cycle holds the topic; this is what holds when two saves race past that check.
CREATE UNIQUE INDEX IF NOT EXISTS uq_study_cycle_topics_active ON study_cycle_topics (topic_id)
    WHERE active;
-- Deleting any notebook page looks here through the foreign key.
CREATE INDEX IF NOT EXISTS idx_study_cycle_topics_topic ON study_cycle_topics (topic_id);

CREATE TABLE IF NOT EXISTS study_cycle_blocks (
    id uuid NOT NULL,
    cycle_id uuid NOT NULL,
    -- 1-based order in the queue.
    -- squawk-ignore prefer-bigint-over-int
    position integer NOT NULL,
    topic_id uuid NOT NULL,
    -- Mirrors BlockMode. Adding a value in Java without adding it here makes every write of the
    -- new mode fail at insert time.
    -- squawk-ignore prefer-text-field
    mode varchar(8) NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    planned_minutes integer NOT NULL,
    -- GOAL blocks only. Mirrors GoalSource, with the same warning as mode.
    -- squawk-ignore prefer-text-field
    goal_source varchar(8),
    -- The chosen page (GOAL + PAGE), or a hint (TIME). Deleting the page leaves the block.
    page_id uuid,
    require_quiz boolean DEFAULT false NOT NULL,
    -- squawk-ignore prefer-text-field
    note varchar(200),
    CONSTRAINT study_cycle_blocks_pkey PRIMARY KEY (id),
    CONSTRAINT study_cycle_blocks_cycle_fkey FOREIGN KEY (cycle_id)
        REFERENCES study_cycles (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_blocks_topic_fkey FOREIGN KEY (topic_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_blocks_page_fkey FOREIGN KEY (page_id)
        REFERENCES notebook_pages (id) ON DELETE SET NULL,
    CONSTRAINT study_cycle_blocks_mode_check CHECK (mode IN ('TIME', 'GOAL')),
    CONSTRAINT study_cycle_blocks_goal_source_check CHECK (goal_source IN ('NEXT', 'PAGE')),
    CONSTRAINT study_cycle_blocks_planned_check CHECK (planned_minutes BETWEEN 5 AND 600)
);

CREATE INDEX IF NOT EXISTS idx_study_cycle_blocks_cycle ON study_cycle_blocks (cycle_id);
CREATE INDEX IF NOT EXISTS idx_study_cycle_blocks_topic ON study_cycle_blocks (topic_id);
CREATE INDEX IF NOT EXISTS idx_study_cycle_blocks_page ON study_cycle_blocks (page_id);

-- One block in one lap. Created lazily, the first time the lap credits, resolves or closes the
-- block, so a block with no row is OPEN with no minutes.
CREATE TABLE IF NOT EXISTS study_cycle_lap_blocks (
    id uuid NOT NULL,
    cycle_id uuid NOT NULL,
    block_id uuid NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    lap_number integer NOT NULL,
    -- Can pass planned_minutes: the topic's last block takes what is left over.
    -- squawk-ignore prefer-bigint-over-int
    minutes integer DEFAULT 0 NOT NULL,
    -- The concept a GOAL block resolved to, kept so the block closes when that page is done.
    target_page_id uuid,
    -- Mirrors LapBlockState. Adding a value in Java without adding it here makes every write of
    -- the new state fail at insert time.
    -- squawk-ignore prefer-text-field
    state varchar(12) DEFAULT 'OPEN' NOT NULL,
    closed_at timestamptz,
    CONSTRAINT study_cycle_lap_blocks_pkey PRIMARY KEY (id),
    CONSTRAINT study_cycle_lap_blocks_cycle_fkey FOREIGN KEY (cycle_id)
        REFERENCES study_cycles (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_lap_blocks_block_fkey FOREIGN KEY (block_id)
        REFERENCES study_cycle_blocks (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_lap_blocks_target_fkey FOREIGN KEY (target_page_id)
        REFERENCES notebook_pages (id) ON DELETE SET NULL,
    CONSTRAINT study_cycle_lap_blocks_state_check CHECK (state IN ('OPEN', 'DONE', 'UNDERSTOOD', 'TURN_USED')),
    CONSTRAINT study_cycle_lap_blocks_minutes_check CHECK (minutes >= 0),
    CONSTRAINT study_cycle_lap_blocks_once UNIQUE (block_id, lap_number)
);

CREATE INDEX IF NOT EXISTS idx_study_cycle_lap_blocks_cycle ON study_cycle_lap_blocks (cycle_id, lap_number);
CREATE INDEX IF NOT EXISTS idx_study_cycle_lap_blocks_target ON study_cycle_lap_blocks (target_page_id);

-- A closed lap, frozen. planned_minutes and turns_used are kept here rather than recomputed
-- from the lap's rows because a block edited or deleted later would change the past.
CREATE TABLE IF NOT EXISTS study_cycle_laps (
    id uuid NOT NULL,
    cycle_id uuid NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    number integer NOT NULL,
    started_at timestamptz NOT NULL,
    closed_at timestamptz NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    minutes integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    planned_minutes integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    understood integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    turns_used integer NOT NULL,
    -- squawk-ignore prefer-bigint-over-int
    blocks integer NOT NULL,
    CONSTRAINT study_cycle_laps_pkey PRIMARY KEY (id),
    CONSTRAINT study_cycle_laps_cycle_fkey FOREIGN KEY (cycle_id)
        REFERENCES study_cycles (id) ON DELETE CASCADE,
    CONSTRAINT study_cycle_laps_once UNIQUE (cycle_id, number)
);

-- Study done outside the app ("Lançar sessão"). studied_on places it in the history; the
-- minutes go into the current lap whatever that date says. cycle_id and lap_number record
-- where they went, so a delete can take them back from the same lap and nowhere else.
CREATE TABLE IF NOT EXISTS study_sessions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    topic_id uuid NOT NULL,
    page_id uuid,
    -- squawk-ignore prefer-bigint-over-int
    minutes integer NOT NULL,
    studied_on date NOT NULL,
    -- squawk-ignore prefer-text-field
    note varchar(200),
    created_at timestamptz NOT NULL,
    cycle_id uuid,
    -- squawk-ignore prefer-bigint-over-int
    lap_number integer,
    CONSTRAINT study_sessions_pkey PRIMARY KEY (id),
    CONSTRAINT study_sessions_user_fkey FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT study_sessions_topic_fkey FOREIGN KEY (topic_id)
        REFERENCES notebook_pages (id) ON DELETE CASCADE,
    CONSTRAINT study_sessions_page_fkey FOREIGN KEY (page_id)
        REFERENCES notebook_pages (id) ON DELETE SET NULL,
    CONSTRAINT study_sessions_cycle_fkey FOREIGN KEY (cycle_id)
        REFERENCES study_cycles (id) ON DELETE SET NULL,
    CONSTRAINT study_sessions_minutes_check CHECK (minutes BETWEEN 1 AND 600)
);

CREATE INDEX IF NOT EXISTS idx_study_sessions_user_day ON study_sessions (user_id, studied_on);
CREATE INDEX IF NOT EXISTS idx_study_sessions_topic ON study_sessions (topic_id);
CREATE INDEX IF NOT EXISTS idx_study_sessions_page ON study_sessions (page_id);
CREATE INDEX IF NOT EXISTS idx_study_sessions_cycle ON study_sessions (cycle_id);
