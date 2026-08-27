-- A second kind of routine. The Daily one has sections with start and end times, and every
-- habit or task inside a section carries its own window. The List one drops both: a flat,
-- ordered list of items the user checks whenever they like during the day.
--
-- A column rather than a second SINGLE_TABLE subclass, deliberately. `routines` is a
-- SINGLE_TABLE hierarchy whose baseline pins dtype to 'DiaryRoutine', and far more than
-- that one constraint assumes a single subclass: CheckItemService casts the routine it
-- reaches through itemGroup.getRoutineSection().getRoutine() to DiaryRoutine on every
-- branch (check, uncheck, skip, unskip), and SnapshotStructureSerializer,
-- ScheduledOnDayResolver, DiaryRoutineMapper, ScheduleService and DiaryRoutineRepository
-- are all typed to it. V13 also put NOT NULL streak columns on this table and says in its
-- own comment that doing so is only safe while DiaryRoutine stands alone. A discriminator
-- column leaves every one of those paths untouched, which is the point: a List routine is
-- scheduled, snapshotted, checked, streaked and levelled by exactly the code that already
-- does it for a Daily one. Only the rendering differs.
--
-- A List routine still stores its items in ONE RoutineSection, created server-side with
-- null times. routine_sections.start_time / end_time and item_groups.start_time are
-- already nullable in the baseline, and SnapshotStructureSerializer.formatTime already
-- returns null for a null time, so nothing downstream needs relaxing. That section is an
-- internal representation; the API takes and returns a flat item list.
--
-- SET LOCAL, not SET — see V13/V14/V19. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- DEFAULT 'DAILY' is what keeps every existing routine, and every client that has never
-- heard of this column, on precisely the behaviour it has today.
ALTER TABLE routines
    -- squawk-ignore prefer-text-field
    ADD COLUMN IF NOT EXISTS routine_type varchar(20) NOT NULL DEFAULT 'DAILY';

-- Mirrored by the RoutineType enum. Adding a value there without adding it here makes every
-- write of the new kind fail, which is the same contract V13 and V19 document.
--
-- NOT VALID then VALIDATE, rather than a plain ADD CONSTRAINT: the plain form takes a scan
-- under a lock that blocks writes, and squawk fails the build over it. Splitting the two
-- keeps the write-blocking window to the catalog update. The validation pass that follows
-- takes only a SHARE UPDATE EXCLUSIVE lock, and it cannot find a violating row anyway —
-- the column was created one statement ago with a default inside the allowed set.
-- squawk-ignore prefer-robust-stmts
ALTER TABLE routines ADD CONSTRAINT routines_routine_type_check CHECK (routine_type IN ('DAILY', 'LIST')) NOT VALID;

-- squawk-ignore prefer-robust-stmts
ALTER TABLE routines VALIDATE CONSTRAINT routines_routine_type_check;

-- Manual ordering needs somewhere to live. routine_sections has carried order_index since
-- the baseline; the items inside them never did, because Daily sorts them by start time and
-- a List routine has no times to sort by.
--
-- Written for Daily routines too, and read by nobody there: Daily keeps ordering by
-- start_time in both clients (routineSection.tsx and sectionItems.ts). Do not drop this
-- column on the grounds that Daily ignores it.
ALTER TABLE item_groups
    -- squawk-ignore prefer-bigint-over-int
    ADD COLUMN IF NOT EXISTS order_index integer NOT NULL DEFAULT 0;
