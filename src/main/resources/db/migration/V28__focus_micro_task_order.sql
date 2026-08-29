-- Micro-tasks keep the order the person put them in.
--
-- Until now the list came back in creation order, which is right until somebody writes the third
-- thing first and wants it moved. Ordering is a property of (user, day, item): each item's list is
-- its own sequence, and the positions of one item's list say nothing about another's.
--
-- Existing rows all land on 0, which is deliberate rather than lazy. `created_at` stays the
-- tiebreaker in the ORDER BY, so every list that exists today comes back in exactly the order it
-- comes back in now, and the first drag is what starts assigning real positions.
--
-- The column is NOT NULL with a constant DEFAULT, which Postgres 11 and later add without rewriting
-- the table.

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Positions in a list somebody types by hand, so the 32-bit ceiling is four orders of magnitude
-- past anything reachable. Written on one line because a squawk ignore applies to the line right
-- below it, and the column sits inside the statement.
-- squawk-ignore prefer-bigint-over-int
ALTER TABLE focus_micro_tasks ADD COLUMN IF NOT EXISTS order_index integer DEFAULT 0 NOT NULL;
