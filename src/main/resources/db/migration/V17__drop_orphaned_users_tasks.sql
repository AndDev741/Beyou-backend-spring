-- Retire users.tasks.
--
-- V1__baseline.sql declares it as `character varying(255)[]`, because the User
-- entity carried a bare `List<String> tasks` that Hibernate mapped as an array of
-- strings. Nothing ever read or wrote it: the real tasks live in their own table,
-- reached through Task.user.
--
-- That field is gone as of this change. It was replaced by the @OneToMany<Task>
-- the entity should always have had — without it Hibernate had no way to carry a
-- user's tasks off with the account, and deleting any account that had ever created
-- a task failed on a transient-reference error. The column it used to map is now
-- orphaned: `ddl-auto: validate` tolerates a table column no entity claims, so
-- nothing breaks either way, but leaving it behind means the next person reading
-- the schema finds a tasks column on users and has to work out that it is a fossil.
--
-- V14__drop_habit_constance.sql is this project's own precedent: the change that
-- unmaps a column is the change that drops it.
--
-- No data is carried across, for the same reason V14 carried none: the column was
-- never populated by any code path in the application's history.
--
-- SET LOCAL, not SET — see the note in V13. Flyway has no datasource of its own, so
-- a session-scoped SET would ride back into the pool serving live requests.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- Squawk ignore: ban-drop-column. The rule guards against dropping a column a
-- deployed reader still selects. There is no such reader: the field is removed in
-- the same change, no view, index, constraint or generated column references it
-- (V1 declares it as a bare nullable array), and Hibernate runs ddl-auto: validate
-- everywhere, so a stale mapping fails at boot rather than at the first query.
--
-- DROP COLUMN is metadata-only in PostgreSQL: an ACCESS EXCLUSIVE lock for the
-- length of a catalogue update, and no table rewrite.
ALTER TABLE users
    -- squawk-ignore ban-drop-column
    DROP COLUMN IF EXISTS tasks;
