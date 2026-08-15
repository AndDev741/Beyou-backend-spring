-- Clear the schedules nobody can reach.
--
-- `routines.schedule_id` is the only reference a schedule ever has: the schedules
-- table is an id and nothing else, schedule_days hangs off it, and no row anywhere
-- names a user. Routine.schedule carried no cascade until now, so every routine that
-- was ever deleted left its schedule and that schedule's days behind, permanently
-- unreachable and permanently growing. Both delete paths did it — deleting a single
-- routine, and deleting an account, which reaches routines through User.routines.
--
-- Found by querying a dev database after an account deletion: zero rows left in the
-- thirteen tables that carry a user_id, and two stranded schedules carrying fourteen
-- day rows between them.
--
-- The cascade is added on the entity in the same change, so this migration is the
-- one-time sweep of what accumulated before it, not a recurring cleanup.
--
-- Nothing here can touch a live schedule: the WHERE clause is "no routine points at
-- this row", and a routine pointing at it is the entire definition of live. A
-- schedule mid-creation is written inside ScheduleService.create's transaction along
-- with the routine that references it, so it is never visible to this statement in
-- the unreferenced state.
--
-- SET LOCAL, not SET — see the note in V13. Flyway has no datasource of its own, so a
-- session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Days first: schedule_days.schedule_id is a plain foreign key with no cascade, so
-- deleting the parent first would fail on it.
DELETE FROM schedule_days d
WHERE NOT EXISTS (
    SELECT 1 FROM routines r WHERE r.schedule_id = d.schedule_id
);

DELETE FROM schedules s
WHERE NOT EXISTS (
    SELECT 1 FROM routines r WHERE r.schedule_id = s.id
);
