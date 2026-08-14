-- Retire habits.constance.
--
-- The column carried two unrelated jobs. It was a lifetime tally of check-ins,
-- incremented on check and decremented on uncheck by hand in three separate
-- writers and never floored at zero, so an uncheck without a matching check drove
-- it negative. It was also the number the XP streak bonus multiplied by, which
-- meant the bonus grew with total days ever checked rather than with an actual
-- streak — a habit checked once a month for two years paid the capped +50%.
--
-- V13 gave habits check_total_check_ins and check_current_streak, both derived
-- from entity_check_day rather than accumulated. U3 moved the tally to the first
-- and the XP bonus to the second, and HabitMapper now reads
-- check_total_check_ins where it read this column. Nothing else touched it.
--
-- No data is carried across. BeYou has never been deployed; the counter's stored
-- values are dev and e2e noise, and the honest replacement is derived from rows
-- that do not exist yet, so a backfill would invent history. Every habit starts
-- from zero and earns its streak forward.

-- Bound the blast radius if this ever runs against a busy database: give up
-- rather than queue behind (or ahead of) live traffic.
--
-- SET LOCAL, not SET — see the note in V13. Plain SET is session-scoped, and
-- Flyway has no datasource of its own, so the connection carries the timeouts
-- back into the pool that serves live requests. LOCAL scopes them to the
-- transaction Flyway wraps this migration in, which still covers the DDL below.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Squawk ignore: ban-drop-column. Dropping a column breaks any deployed reader
-- still selecting it, which is what the rule guards. There is no such reader
-- here: `constance` is not referenced by any view, index, constraint, trigger or
-- generated column (V1__baseline.sql declares it as a bare nullable integer), the
-- only application reference was Habit.constance, removed in the same change, and
-- Hibernate runs ddl-auto: validate everywhere — a stale mapping fails at boot
-- rather than at the first query. The alternative dance (stop writing, deploy,
-- drop later) protects a rolling deploy against a live client; there is neither.
--
-- DROP COLUMN is metadata-only in PostgreSQL, so this takes an ACCESS EXCLUSIVE
-- lock for the length of a catalogue update and no table rewrite.
ALTER TABLE habits
    -- squawk-ignore ban-drop-column
    DROP COLUMN IF EXISTS constance;
