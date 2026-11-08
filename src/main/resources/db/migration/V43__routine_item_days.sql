-- Routine item days: an item inside a routine can be limited to some weekdays.
--
-- The case that asked for it: a routine runs on Saturday and Sunday, and "clean the fish tank"
-- belongs in it only on Saturday. Before this table the only way to say that was a second
-- routine, and the one-routine-per-weekday rule made even that impossible.
--
-- No rows means "every day the routine runs", which is what every item meant before this
-- migration, so nothing is backfilled and no existing routine changes behaviour. With rows, an
-- item runs on a day when the routine's schedule covers it AND the day is listed here.
-- ScheduledOnDayResolver.itemActiveOn holds that rule and every reader goes through it.
--
-- The days live on the item group (the habit's or task's place in one routine), not on the
-- habit or task: the same habit can sit in two routines with different days.
--
-- Same shape as schedule_days, so ItemGroup.days maps the way Schedule.days does. ON DELETE
-- CASCADE because item groups are deleted in bulk by the account-deletion runbook
-- (src/test/resources/runbooks/manual-account-delete.sql) as well as by JPA orphan removal,
-- and a plain foreign key would make the runbook fail on the first item with days.
--
-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V31/V40. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS item_group_days (
    item_group_id uuid NOT NULL,
    -- Mirrors WeekDay, exactly like schedule_days_days_check. Adding a value in Java without
    -- adding it here makes every write of the new day fail at insert time.
    -- squawk-ignore prefer-text-field
    days varchar(20) NOT NULL,
    CONSTRAINT item_group_days_pkey PRIMARY KEY (item_group_id, days),
    CONSTRAINT item_group_days_item_group_fkey FOREIGN KEY (item_group_id)
        REFERENCES item_groups (id) ON DELETE CASCADE,
    CONSTRAINT item_group_days_days_check
        CHECK (days IN ('Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'))
);
