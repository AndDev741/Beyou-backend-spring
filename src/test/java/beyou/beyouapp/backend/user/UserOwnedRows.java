package beyou.beyouapp.backend.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One row in every table that hangs off a user and that the application's services cannot
 * cheaply reach from a test, written straight with SQL.
 *
 * <p>Exists because the deletion tests used to seed only what existed when they were written.
 * Every table added after that (mood, focus, the whole notebook, federated sign-ins, the
 * briefing) cascades from {@code users} in its migration, and two of those migrations say a
 * deletion test proves it. None did. A cascade nobody exercises is a comment, so both deletion
 * tests now seed a row here per table and check every one is gone.
 *
 * <p>The schema is the source of truth for what "every table" means. {@link #foreignKeysToUsers}
 * reads the live list of foreign keys pointing at {@code users}, and the tests fail when a table
 * appears there that nothing seeds and nothing exempts. That is the point: the next user-owned
 * table fails a test the day it is added, instead of waiting for someone to remember this file.
 *
 * <p>SQL rather than services on purpose. Several of these rows are written by schedulers or by
 * a model's reply in the running app, and the deletion question does not care how a row got
 * there, only that it goes.
 */
public final class UserOwnedRows {

    /**
     * Tables this class writes a row into. A table added to {@link #seed} goes here too, and a
     * user-owned table in neither this set nor a test's own list fails that test.
     */
    public static final Set<String> SEEDED = Set.of(
            "refresh_tokens", "password_reset_tokens", "entity_check_day", "entity_xp_day",
            "routine_snapshot", "notification_preferences", "notification_sends",
            "focus_cycles", "focus_micro_tasks", "federated_identities", "mood_entries",
            "daily_briefing", "notebook_pages", "notebook_board_nodes", "notebook_board_edges",
            "notebook_cards", "notebook_card_reviews", "notebook_sources", "notebook_source_chunks",
            "notebook_study_outputs", "notebook_chat_messages", "notebook_roadmap_drafts");

    /** What the export assertions need to find again. */
    public record Seeded(UUID topicId, UUID pageId, UUID sectionNodeId, UUID pageNodeId, UUID cardId,
                  UUID itemGroupId, LocalDate day) {
    }

    private UserOwnedRows() {
    }

    /**
     * Writes the rows.
     *
     * @param itemGroupId a habit or task group inside one of this user's routines. Focus rows
     *                    point at one, and a micro-task cannot exist without it
     * @param routineId   a routine of this user's, for the snapshot row
     * @param habitId     a habit of this user's. The notebook topic links to it, so a delete has
     *                    to clear that link before it can drop the habit
     * @param categoryId  a category of this user's, linked the same way
     */
    public static Seeded seed(JdbcTemplate jdbc, UUID userId, UUID itemGroupId, UUID routineId,
                       UUID habitId, UUID categoryId) {
        return seed(jdbc, userId, itemGroupId, routineId, habitId, categoryId, 0);
    }

    /**
     * The same, as the {@code k}-th copy for one account. Several tables allow one row per user
     * per day, so each copy lands on its own day; the query-count test seeds a few of these to
     * see whether the export's cost grows with them.
     */
    public static Seeded seed(JdbcTemplate jdbc, UUID userId, UUID itemGroupId, UUID routineId,
                       UUID habitId, UUID categoryId, int k) {
        Timestamp now = Timestamp.from(Instant.now());
        // Far enough back that no service call in the same test lands on the same day.
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(400L + k);

        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, created_at, expires_at) "
                + "VALUES (?, ?, 'seeded-refresh', ?, ?)", UUID.randomUUID(), userId, now, now);
        jdbc.update("INSERT INTO password_reset_tokens (id, user_id, token_hash, created_at, expires_at) "
                + "VALUES (?, ?, 'seeded-reset', ?, ?)", UUID.randomUUID(), userId, now, now);
        jdbc.update("INSERT INTO entity_check_day (id, user_id, owner_type, owner_id, day, outcome) "
                + "VALUES (?, ?, 'USER', ?, ?, 'DONE') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), userId, userId, day);
        jdbc.update("INSERT INTO entity_xp_day (id, user_id, owner_type, owner_id, day, xp) "
                + "VALUES (?, ?, 'USER', ?, ?, 12) ON CONFLICT DO NOTHING",
                UUID.randomUUID(), userId, userId, day);

        UUID snapshotId = UUID.randomUUID();
        jdbc.update("INSERT INTO routine_snapshot (id, user_id, routine_id, snapshot_date, completed, "
                + "routine_name, structure_json) VALUES (?, ?, ?, ?, false, 'Morning', '{}')",
                snapshotId, userId, routineId, day);
        jdbc.update("INSERT INTO snapshot_check (id, snapshot_id, checked, skipped, difficulty, "
                + "importance, xp_generated, item_name, item_type, section_name) "
                + "VALUES (?, ?, true, false, 2, 3, 10, 'Drink water', 'HABIT', 'Wake up')",
                UUID.randomUUID(), snapshotId);

        jdbc.update("INSERT INTO notification_preferences (user_id, engagement_email, unsubscribe_token, "
                + "created_at, updated_at) VALUES (?, false, ?, ?, ?) ON CONFLICT DO NOTHING",
                userId, UUID.randomUUID().toString(), now, now);
        jdbc.update("INSERT INTO notification_sends (id, user_id, kind, sent_on, created_at) "
                + "VALUES (?, ?, 'STREAK_RECORD_AT_RISK', ?, ?)", UUID.randomUUID(), userId, day, now);

        UUID topicId = UUID.randomUUID();
        UUID pageId = UUID.randomUUID();
        jdbc.update("INSERT INTO notebook_pages (id, user_id, kind, title, category_id, habit_id, "
                + "created_at, updated_at) VALUES (?, ?, 'TOPIC', 'Spanish', ?, ?, ?, ?)",
                topicId, userId, categoryId, habitId, now, now);
        jdbc.update("INSERT INTO notebook_pages (id, user_id, kind, parent_id, topic_id, title, "
                + "content_text, created_at, updated_at) "
                + "VALUES (?, ?, 'PAGE', ?, ?, 'Verbs', 'ser and estar are both to be', ?, ?)",
                pageId, userId, topicId, topicId, now, now);

        UUID sectionNodeId = UUID.randomUUID();
        UUID pageNodeId = UUID.randomUUID();
        jdbc.update("INSERT INTO notebook_board_nodes (id, user_id, board_page_id, page_id, kind, label, "
                + "x, y, created_at) VALUES (?, ?, ?, NULL, 'SECTION', 'Grammar', 10, 20, ?)",
                sectionNodeId, userId, topicId, now);
        jdbc.update("INSERT INTO notebook_board_nodes (id, user_id, board_page_id, page_id, kind, "
                + "x, y, created_at) VALUES (?, ?, ?, ?, 'PAGE', 30, 40, ?)",
                pageNodeId, userId, topicId, pageId, now);
        jdbc.update("INSERT INTO notebook_board_edges (id, user_id, board_page_id, source_node_id, "
                + "target_node_id) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, topicId, sectionNodeId, pageNodeId);

        UUID cardId = UUID.randomUUID();
        jdbc.update("INSERT INTO notebook_cards (id, user_id, page_id, front, back, due_on, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'ser', 'to be (for what something is)', ?, ?, ?)",
                cardId, userId, pageId, day, now, now);
        jdbc.update("INSERT INTO notebook_card_reviews (id, user_id, card_id, rating, review_date, "
                + "reviewed_at) VALUES (?, ?, ?, 'GOOD', ?, ?)", UUID.randomUUID(), userId, cardId, day, now);

        UUID sourceId = UUID.randomUUID();
        jdbc.update("INSERT INTO notebook_sources (id, user_id, page_id, kind, title, status, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'TEXT', 'Class notes', 'READY', ?, ?)",
                sourceId, userId, pageId, now, now);
        jdbc.update("INSERT INTO notebook_source_chunks (id, source_id, user_id, ordinal, content) "
                + "VALUES (?, ?, ?, 0, 'the text of the class notes')", UUID.randomUUID(), sourceId, userId);
        jdbc.update("INSERT INTO notebook_study_outputs (id, user_id, page_id, kind, title, content, score, "
                + "total, created_at) VALUES (?, ?, ?, 'QUIZ', 'Verbs quiz', '{\"questions\":[]}', 4, 5, ?)",
                UUID.randomUUID(), userId, pageId, now);
        jdbc.update("INSERT INTO notebook_chat_messages (id, user_id, page_id, role, content, created_at) "
                + "VALUES (?, ?, ?, 'USER', 'when do I use estar?', ?)", UUID.randomUUID(), userId, pageId, now);
        jdbc.update("INSERT INTO notebook_roadmap_drafts (id, user_id, title, status, request, started_at, "
                + "created_at, updated_at) VALUES (?, ?, 'Spanish in a month', 'READY', "
                + "'{\"title\":\"Spanish in a month\"}', ?, ?, ?)",
                UUID.randomUUID(), userId, now, now, now);

        jdbc.update("INSERT INTO focus_cycles (id, user_id, cycle_date, item_group_id, kind, started_at, "
                + "ended_at, minutes, notebook_page_id) VALUES (?, ?, ?, ?, 'POMODORO', ?, ?, 25, ?)",
                UUID.randomUUID(), userId, day, itemGroupId, now, now, pageId);
        jdbc.update("INSERT INTO focus_micro_tasks (id, user_id, task_date, item_group_id, name, pinned, "
                + "created_at, order_index) VALUES (?, ?, ?, ?, 'fill the bottle', true, ?, 0)",
                UUID.randomUUID(), userId, day, itemGroupId, now);

        jdbc.update("INSERT INTO federated_identities (id, user_id, issuer, subject, email_at_link, "
                + "created_at, last_login_at) VALUES (?, ?, 'https://id.example.test', ?, ?, ?, ?)",
                UUID.randomUUID(), userId, "subject-" + userId + "-" + k, "linked@example.test", now, now);
        jdbc.update("INSERT INTO mood_entries (id, user_id, entry_date, mood, note, created_at, updated_at) "
                + "VALUES (?, ?, ?, 3, 'a seeded day', ?, ?)", UUID.randomUUID(), userId, day, now, now);
        jdbc.update("INSERT INTO daily_briefing (id, user_id, briefing_date, narrative_json, "
                + "narrative_status, seen_at, created_at) VALUES (?, ?, ?, ?, 'READY', ?, ?)",
                UUID.randomUUID(), userId, day,
                "{\"todayLines\":[\"Two habits left.\"],\"yesterdayLines\":[\"A full day.\"]}", now, now);

        return new Seeded(topicId, pageId, sectionNodeId, pageNodeId, cardId, itemGroupId, day);
    }

    /** A foreign key column that points at {@code users.id}. */
    public record UserForeignKey(String table, String column) {
    }

    /** Every foreign key column pointing at {@code users}, read from the live schema. */
    public static List<UserForeignKey> foreignKeysToUsers(JdbcTemplate jdbc) {
        return new ArrayList<>(jdbc.query("""
                SELECT c.conrelid::regclass::text AS tbl, a.attname AS col
                  FROM pg_constraint c
                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
                 WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass
                 ORDER BY 1, 2
                """, (rs, n) -> new UserForeignKey(rs.getString("tbl"), rs.getString("col"))));
    }

    /** The tables in that list, deduplicated and sorted so a failure message reads cleanly. */
    public static Set<String> tablesPointingAtUsers(JdbcTemplate jdbc) {
        Set<String> tables = new TreeSet<>();
        foreignKeysToUsers(jdbc).forEach(fk -> tables.add(fk.table()));
        return tables;
    }

    /** Rows in {@code table} whose {@code column} still names this user. */
    public static long rowsFor(JdbcTemplate jdbc, String table, String column, UUID userId) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, userId);
        return rows == null ? 0 : rows;
    }
}
