-- Check/streak metadata for every checkable entity.
--
-- Two halves (KTD2):
--   1. the rolling scalars (current streak, best streak, lifetime check-ins,
--      first and last check-in day) embedded on habits, tasks, routines and
--      users, via the CheckProgress @Embeddable;
--   2. entity_check_day, one permanent row per entity per day carrying exactly
--      one outcome (R5, R6).
--
-- The scalars are embedded rather than joined because they are read on every
-- render of the owning entity. The history is a side table rather than embedded
-- because it grows without bound and SecurityFilter loads the whole users row on
-- every authenticated request — an @Embeddable cannot be lazy, so a per-day
-- structure mapped onto users would be read in full, forever.
--
-- No seeding, no backfill, no copying from the older constance counters: BeYou
-- has never been deployed and holds no data worth preserving. Existing dev and
-- e2e rows just take the zero defaults.

-- Bound the blast radius if this ever runs against a busy database: give up
-- rather than queue behind (or ahead of) live traffic.
--
-- SET LOCAL, not SET. Plain SET is session-scoped, and Flyway is not given a
-- datasource of its own — it borrows a connection from the pool that serves live
-- requests and hands it straight back. A session-scoped statement_timeout would
-- ride that connection back into the pool and start cancelling ordinary queries
-- at 60s, on whichever connection happened to run the migration and no other:
-- an intermittent failure that follows a single pooled connection around.
-- LOCAL scopes both to the transaction, and Flyway wraps each migration in one,
-- so the DDL below is still covered and nothing survives the commit.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- The three counters are NOT NULL DEFAULT 0 on purpose, and the @Embeddable's
-- field initialisers match. Hibernate materialises a NULL embeddable reference
-- when every column of an embeddable is null on a row, so nullable counters
-- would hand back checkProgress == null for every row written before this
-- migration — the exact trap HabitMapper already works around for xpProgress.
-- The two dates stay nullable: "never checked anything" is a real state and
-- deserves a null, not an invented date.
--
-- DEFAULT 0 on ADD COLUMN is metadata-only from PostgreSQL 11 onwards (no table
-- rewrite), so this is cheap even once these tables are large.
--
-- Squawk ignores: prefer-bigint-over-int on every counter. These are day counts
-- — a streak, a best streak, a lifetime tally of days checked. A 32-bit int runs
-- out after 2.1 billion days, which is about 5.8 million years of daily
-- check-ins; the rule is guarding against a ceiling this shape of number cannot
-- reach. The type also has to stay `integer` to match the Java `int` fields on
-- CheckProgress: Hibernate runs ddl-auto: validate in every environment and
-- rejects a bigint column mapped to an int field outright.

ALTER TABLE habits
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_current_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_best_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_total_check_ins integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS check_first_check_in_date date,
    ADD COLUMN IF NOT EXISTS check_last_check_in_date date;

-- R4: one-time tasks never accumulate a streak, so their columns simply stay at
-- the defaults. Nothing writes to them and nothing reads them as meaningful.
ALTER TABLE tasks
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_current_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_best_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_total_check_ins integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS check_first_check_in_date date,
    ADD COLUMN IF NOT EXISTS check_last_check_in_date date;

-- routines is a SINGLE_TABLE hierarchy, so NOT NULL here binds every subclass.
-- Safe today because DiaryRoutine is the only one (see routines_dtype_check in
-- V1__baseline.sql); a future unchecked subclass would need these relaxed.
ALTER TABLE routines
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_current_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_best_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_total_check_ins integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS check_first_check_in_date date,
    ADD COLUMN IF NOT EXISTS check_last_check_in_date date;

-- The account-wide streak. Deliberately separate from users.max_constance and
-- users.completed_days, which the older constance model owns; nothing here
-- reads or writes those.
ALTER TABLE users
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_current_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_best_streak integer NOT NULL DEFAULT 0,
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS check_total_check_ins integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS check_first_check_in_date date,
    ADD COLUMN IF NOT EXISTS check_last_check_in_date date;

-- Per-day outcome history, retained indefinitely (R5).
--
-- Squawk ignores (per line): owner_type and outcome are bounded enum-like
-- strings mirroring the CheckDayOwnerType / CheckDayOutcome enums, with CHECK
-- constraints keeping the two in step — same shape as feedback.category in V9
-- and goals.status in V1. New table on a pre-production database.
CREATE TABLE IF NOT EXISTS entity_check_day (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    -- squawk-ignore prefer-text-field
    owner_type varchar(16) NOT NULL,
    -- No foreign key on purpose: R8 wants a day's history to outlive the
    -- routine it was recorded through and to survive routine edits. Modelled on
    -- snapshot_check.original_item_id, which holds a bare id for the same
    -- reason. Deleting the owning habit or task does clear its history, but by
    -- an explicit delete in application code, not by the database's choice.
    owner_id uuid NOT NULL,
    day date NOT NULL,
    -- squawk-ignore prefer-text-field
    outcome varchar(16) NOT NULL,
    CONSTRAINT pk_entity_check_day PRIMARY KEY (id),
    CONSTRAINT entity_check_day_owner_type_check
        CHECK (owner_type IN ('HABIT', 'TASK', 'ROUTINE', 'USER')),
    CONSTRAINT entity_check_day_outcome_check
        CHECK (outcome IN ('DONE', 'SKIPPED', 'MISSED', 'NOT_SCHEDULED', 'NOT_IN_ROUTINE')),
    -- R5 — exactly one outcome per entity per day, enforced here rather than by
    -- whichever writer happens to run first. Also the index the per-entity
    -- history read rides.
    CONSTRAINT uk_entity_check_day_owner_day UNIQUE (owner_type, owner_id, day),
    -- Deleting an account takes its history with it, so account deletion can
    -- never be blocked by a foreign key it does not know about — the trap
    -- documented on UserService.deleteUser.
    CONSTRAINT fk_entity_check_day_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Closing out a day writes one row per owner for one user; the export reads one
-- user across a range. Both lead with user_id and filter on day.
CREATE INDEX IF NOT EXISTS idx_entity_check_day_user_day
ON entity_check_day(user_id, day);
