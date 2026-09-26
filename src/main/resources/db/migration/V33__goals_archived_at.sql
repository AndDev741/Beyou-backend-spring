-- Archived goals: put away without deleting.
--
-- A timestamp, not a boolean, and not a fourth GoalStatus. Status already carries the
-- complete/status invariant V15 repaired (COMPLETED means `complete` is true and the XP was
-- paid), and an archived goal can be finished or abandoned: archiving is orthogonal to how
-- far it got. NULL is an active goal. The timestamp also records WHICH goals were archived
-- together. Archiving a goal stamps its sub-goals with the same instant, and unarchiving
-- restores only the rows carrying that instant, so a sub-goal archived on its own earlier
-- stays archived. GoalService.setArchived owns that rule.
--
-- Nullable with no default, so this is a catalog-only change on Postgres 11+: no table
-- rewrite and no scan.
--
-- SET LOCAL, not SET: see V13/V14/V19/V26/V27/V30/V31/V32. Flyway has no datasource of its
-- own, so a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE goals ADD COLUMN IF NOT EXISTS archived_at timestamptz;
