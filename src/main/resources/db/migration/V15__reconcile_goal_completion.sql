-- Make goals.complete and goals.status agree.
--
-- The two columns say the same thing twice, and until now nothing kept them in
-- step. `complete` is flipped by PUT /goal/complete, which is also where the XP
-- is added or removed. `status` was whatever the last write left there, and the
-- mobile goal form let a user pick "Completed" straight from the edit screen
-- while sending complete=false.
--
-- The cards read `status` and the toggle reads `complete`, so a row where they
-- disagree renders an "Undo" button that completes the goal (paying XP) instead
-- of undoing it, and the screen does not change because the status was already
-- COMPLETED. The same disagreement the other way round turns "Complete" into a
-- button that silently takes XP away.
--
-- The application side now keeps the invariant: the create path refuses to start
-- a goal as COMPLETED, and an edit carries completion forward untouched. This
-- migration cleans up the rows written before that.
--
-- `complete` wins, because it is the column the XP ledger followed. A goal with
-- complete=true was paid for and stays COMPLETED. A goal with complete=false was
-- never paid, so its status drops back to what its progress says it is.
--
-- SET LOCAL, not SET — see the note in V13/V14. Flyway has no datasource of its
-- own, so a session-scoped SET would ride back into the pool that serves live
-- requests. LOCAL scopes both to the transaction Flyway wraps this in.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Paid for, so it is done regardless of what the status column says.
UPDATE goals
SET status = 'COMPLETED'
WHERE complete = true
  AND status <> 'COMPLETED';

-- Never paid for, so it is not done. Progress decides which of the two open
-- statuses it lands on, the same rule the increment endpoint now applies.
UPDATE goals
SET status = CASE WHEN current_value > 0 THEN 'IN_PROGRESS' ELSE 'NOT_STARTED' END
WHERE complete = false
  AND status = 'COMPLETED';
