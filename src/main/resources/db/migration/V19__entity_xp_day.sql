-- One row per XP-bearing entity per day, carrying what it gained or lost that day.
--
-- Everything about XP in this schema is a running total. `categories.xp` says where a
-- category stands and nothing about how it got there, and the same is true of habits,
-- routines and the user. So a chart of the last week has no data to draw, and the
-- widgets that wanted one shipped without it — see the comment in betterArea.tsx and
-- the redesign notes: those components were written to degrade rather than invent a
-- series.
--
-- Modelled on entity_check_day (V13), which solves exactly this problem for check-ins,
-- and generalised for the same reason it was: four entities embed XpProgress (User,
-- Category, Habit, Routine) and every one of them can be asked the same question. A
-- table per owner would be four tables, four repositories and four read paths for one
-- idea. XpCalculatorService already touches all four in a single call, so recording
-- them together costs one write per entity and nothing in complexity.
--
-- A daily bucket rather than an event log, again following V13: the question is always
-- "how much on that day", never "which check-in at what minute". It keeps the table
-- proportional to days-an-entity-was-touched instead of to every action ever taken.
--
-- `xp` is a net delta and may be negative: unchecking a habit takes its XP back, and
-- the day's bar has to shrink with it rather than remember a high-water mark.
--
-- The day comes from the account's own timezone, like every other day here. Someone
-- checking a habit at 23:00 in São Paulo is spending Tuesday, not Wednesday.
--
-- owner_id carries no foreign key, exactly as in entity_check_day and for the reason
-- given there: it is polymorphic, so no single key can point at it, and history is
-- supposed to outlive the routine it was recorded through. Deleting the entity clears
-- its series through an explicit delete, the way HabitService and TaskService already
-- do for check days. user_id IS a real association with ON DELETE CASCADE, because
-- account deletion has to take this table with it rather than be blocked by it.
--
-- SET LOCAL, not SET — see V13/V14. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS entity_xp_day (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    owner_type varchar(16) NOT NULL,
    owner_id uuid NOT NULL,
    day date NOT NULL,
    -- double precision, matching XpProgress.xp. The values are small, but making the
    -- history a different numeric type from the total it accumulates into is how the
    -- two quietly stop agreeing.
    xp double precision NOT NULL DEFAULT 0,
    CONSTRAINT pk_entity_xp_day PRIMARY KEY (id),
    -- Mirrored by XpDayOwnerType. Adding a value there without adding it here makes
    -- every insert of the new kind fail, which is the same contract V13 documents.
    CONSTRAINT entity_xp_day_owner_type_check
        CHECK (owner_type IN ('USER', 'CATEGORY', 'HABIT', 'ROUTINE')),
    -- The upsert's conflict target. One bucket per entity per day, which is what makes
    -- the write idempotent under concurrent check-ins.
    CONSTRAINT uk_entity_xp_day_owner_day UNIQUE (owner_type, owner_id, day),
    CONSTRAINT fk_entity_xp_day_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
);

-- The only read: one user's entities across a window of days, every owner type at once.
CREATE INDEX IF NOT EXISTS idx_entity_xp_day_user_day
    ON entity_xp_day(user_id, day);
