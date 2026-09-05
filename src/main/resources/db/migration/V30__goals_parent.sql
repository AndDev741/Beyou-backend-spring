-- Nested goals: a goal may sit under another goal of the same user.
--
-- One nullable self-referencing column, not a second entity. Every read path already
-- loads all of a user's goals in one query (GoalService.getAllGoals), so the tree is
-- assembled in memory from that list and no query shape changes. The rules that make
-- the column safe (same owner, no cycle, at most three levels) live in
-- GoalService.resolveParent, not in SQL: a recursive trigger is the kind of thing
-- nobody finds when it fails, and both clients already route every write through
-- that one service.
--
-- ON DELETE SET NULL rather than CASCADE. Deleting a big goal with eight sub-goals
-- must not take the eight with it in silence; the children become top-level goals
-- and the UI says so before the delete.
--
-- SET LOCAL, not SET — see V13/V14/V19/V26/V27. Flyway has no datasource of its own,
-- so a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE goals ADD COLUMN IF NOT EXISTS parent_id uuid;

-- A goal is never its own parent. Cycles longer than one edge are refused in the
-- service, where the whole chain is already in memory. The column was added one
-- statement ago and is NULL on every row, so the scan the constraint needs is
-- instant; the ignore below says so where squawk reads it (per line, like V11).
-- squawk-ignore constraint-missing-not-valid, prefer-robust-stmts
ALTER TABLE goals ADD CONSTRAINT goals_parent_not_self CHECK (parent_id IS NULL OR parent_id <> id);

-- Same reasoning for the foreign key: nothing to validate yet, so the SHARE ROW
-- EXCLUSIVE lock is held for no time worth measuring on this pre-production
-- table (V2 rationale). NOT VALID plus VALIDATE would be two statements squawk
-- then flags for not being in a transaction, which Flyway already provides.
-- squawk-ignore constraint-missing-not-valid, adding-foreign-key-constraint, prefer-robust-stmts
ALTER TABLE goals ADD CONSTRAINT fk_goals_parent FOREIGN KEY (parent_id) REFERENCES goals (id) ON DELETE SET NULL;

-- The children lookup used by SET NULL on delete and by any per-parent query.
CREATE INDEX IF NOT EXISTS idx_goals_parent_id ON goals (parent_id);
