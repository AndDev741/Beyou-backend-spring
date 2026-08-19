-- Deleting one account by hand, when the route cannot run.
--
-- The tested path is POST /user/deletion/confirm. This exists for the case where the
-- application is down or the route itself is what broke, and it is a last resort.
--
-- Why it is this long: only seven foreign keys in the whole schema cascade at the
-- database level (feedback and its children, agent_message, entity_check_day,
-- account_deletion_codes, and feedback_reply.author_id which nulls). Everything else
-- is carried off by Hibernate's @OneToMany(cascade = ALL), which a psql session does
-- not have. An earlier version of this procedure said "sections/groups follow" and
-- "the join tables follow their owning row"; neither is true without the ORM, so it
-- would have stopped on a foreign-key violation partway through, on an account
-- somebody was already having a bad day about.
--
-- This file is executed verbatim by ManualAccountDeleteRunbookTest against a real
-- schema, on an account seeded through the application's own services. If the schema
-- grows a table this misses, that test fails. Keep it that way: the value here is not
-- the SQL, it is that the SQL is known to work.
--
-- Usage: psql -v userId="'<uuid>'" -f manual-account-delete.sql
-- Statements are separated by a blank line for the test runner. Keep that convention.

BEGIN;

-- 1. Checks, and the JOINED-inheritance parent rows behind them. The child holds the
-- FK to base_checks, so the child goes first and the parent id has to be captured on
-- the way past — hence the CTE rather than two plain statements.
WITH gone AS (
    DELETE FROM habit_group_checks WHERE habit_group_id IN (
        SELECT hg.id FROM habit_groups hg
          JOIN routine_sections_habit_groups rshg ON rshg.habit_groups_id = hg.id
          JOIN routine_sections rs ON rs.id = rshg.routine_section_id
          JOIN routines r ON r.id = rs.routine_id WHERE r.user_id = :userId)
    RETURNING id)
DELETE FROM base_checks WHERE id IN (SELECT id FROM gone);

WITH gone AS (
    DELETE FROM task_group_checks WHERE task_group_id IN (
        SELECT tg.id FROM task_groups tg
          JOIN routine_sections_task_groups rstg ON rstg.task_groups_id = tg.id
          JOIN routine_sections rs ON rs.id = rstg.routine_section_id
          JOIN routines r ON r.id = rs.routine_id WHERE r.user_id = :userId)
    RETURNING id)
DELETE FROM base_checks WHERE id IN (SELECT id FROM gone);

-- 2. The section-to-group join tables, before the groups they point at.
DELETE FROM routine_sections_habit_groups WHERE routine_section_id IN (
    SELECT rs.id FROM routine_sections rs
      JOIN routines r ON r.id = rs.routine_id WHERE r.user_id = :userId);

DELETE FROM routine_sections_task_groups WHERE routine_section_id IN (
    SELECT rs.id FROM routine_sections rs
      JOIN routines r ON r.id = rs.routine_id WHERE r.user_id = :userId);

-- 3. Groups, and their item_groups parents. Same JOINED-inheritance shape as the
-- checks above: the subclass table holds the FK to the parent table.
WITH gone AS (
    DELETE FROM habit_groups WHERE habit_id IN (
        SELECT id FROM habits WHERE user_id = :userId)
    RETURNING id)
DELETE FROM item_groups WHERE id IN (SELECT id FROM gone);

WITH gone AS (
    DELETE FROM task_groups WHERE task_id IN (
        SELECT id FROM tasks WHERE user_id = :userId)
    RETURNING id)
DELETE FROM item_groups WHERE id IN (SELECT id FROM gone);

-- Any group left over belongs to a section of this user's routines but was already
-- detached from its habit or task. Cheap to include, and the alternative is a foreign
-- key violation at step 5.
DELETE FROM item_groups WHERE routine_section_id IN (
    SELECT rs.id FROM routine_sections rs
      JOIN routines r ON r.id = rs.routine_id WHERE r.user_id = :userId);

-- 4. Snapshots, then sections.
DELETE FROM snapshot_check WHERE snapshot_id IN (
    SELECT id FROM routine_snapshot WHERE user_id = :userId);

DELETE FROM routine_snapshot WHERE user_id = :userId;

DELETE FROM routine_sections WHERE routine_id IN (
    SELECT id FROM routines WHERE user_id = :userId);

-- 5. Routines, and then the schedules they were the only reference to. This order is
-- forced: routines.schedule_id points AT schedules, so the schedule cannot go first.
-- A schedule with no routine is unreachable — the table is an id and nothing else —
-- so skipping this leaves rows nobody can ever find or attribute.
CREATE TEMP TABLE doomed_schedules ON COMMIT DROP AS
    SELECT schedule_id AS id FROM routines
     WHERE user_id = :userId AND schedule_id IS NOT NULL;

DELETE FROM routines WHERE user_id = :userId;

DELETE FROM schedule_days WHERE schedule_id IN (SELECT id FROM doomed_schedules);

DELETE FROM schedules WHERE id IN (SELECT id FROM doomed_schedules);

-- 6. The category join tables, before both sides of them.
DELETE FROM habit_category WHERE habit_id IN (SELECT id FROM habits WHERE user_id = :userId);

DELETE FROM task_category WHERE task_id IN (SELECT id FROM tasks WHERE user_id = :userId);

DELETE FROM goal_category WHERE goal_id IN (SELECT id FROM goals WHERE user_id = :userId);

DELETE FROM habits WHERE user_id = :userId;

DELETE FROM tasks WHERE user_id = :userId;

DELETE FROM goals WHERE user_id = :userId;

DELETE FROM categories WHERE user_id = :userId;

-- 7. The plain foreign keys that block the users row outright.
DELETE FROM refresh_tokens WHERE user_id = :userId;

DELETE FROM password_reset_tokens WHERE user_id = :userId;

DELETE FROM chats WHERE user_id = :userId;

-- 8. The account. feedback, entity_check_day and account_deletion_codes are the only
-- three that really do cascade from here.
DELETE FROM users WHERE id = :userId;

COMMIT;

-- 9. Only after the commit succeeded, and only for feedback ids collected BEFORE step
-- 8 (the rows are gone by now):
--     rm -rf "$UPLOAD_DIR/feedback-attachments/<feedbackId>"
--     rm -f  "$UPLOAD_DIR/user-photos/<userId>.jpg"
-- The profile photo needs no id collected in advance — it is named after the account.
-- If the commit failed, stop and keep the files. The bytes are the one part of this
-- that no transaction can give back.
