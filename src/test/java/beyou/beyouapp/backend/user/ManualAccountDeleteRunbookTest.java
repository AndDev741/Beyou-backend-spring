package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.routine.RoutineType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackReplyService;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackReplyRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manual deletion procedure, executed rather than described.
 *
 * {@code UserService#deleteUser} carries a runbook for deleting one account by hand
 * when the route cannot run. It is the documented fallback for an irreversible,
 * legally-required operation, which is exactly the kind of text nobody runs until the
 * worst possible moment — and the version before this test claimed that routine
 * sections, item groups and the category join tables followed their owning rows. They
 * do not. None of the V1 foreign keys cascade in the database; those are Hibernate's
 * doing, and a psql session has no Hibernate. Followed literally, it would have stopped
 * on a foreign-key violation partway through a half-deleted account.
 *
 * Tables added later mostly do cascade in the database, which makes them easy to forget
 * here and easy to trust without checking. So the account below also carries a row in
 * every one of them ({@link UserOwnedRows}), and the run is judged against every column
 * in the schema that points at a user, not against a list typed into this file.
 *
 * So the procedure now lives in {@code runbooks/manual-account-delete.sql} and this
 * runs it, verbatim, against the real schema on an account seeded through the
 * application's own services. Prose can drift from the schema; this cannot.
 */
class ManualAccountDeleteRunbookTest extends AbstractIntegrationTest {

    private static final String EMAIL = "runbook-manual-delete@beyou.test";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CategoryService categoryService;
    @Autowired HabitService habitService;
    @Autowired TaskService taskService;
    @Autowired DiaryRoutineService diaryRoutineService;
    @Autowired ScheduleService scheduleService;
    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbc;
    @Autowired GoalService goalService;
    @Autowired FeedbackService feedbackService;
    @Autowired FeedbackReplyService feedbackReplyService;

    /**
     * The user-owned tables the test below fills through the application's own services or a
     * line of its own. The rest come from {@link UserOwnedRows}.
     */
    private static final Set<String> SEEDED_HERE = Set.of(
            "categories", "habits", "tasks", "goals", "routines", "chats",
            "account_deletion_codes", "feedback", "feedback_reply");

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));

        User fresh = new User();
        fresh.setName("an account an operator has to remove");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);
    }

    @Test
    @DisplayName("the operator runbook actually runs, on an account that has been used")
    void theRunbookDeletesAFullAccount() throws IOException {
        UUID userId = user.getId();

        categoryService.createCategory(new CategoryRequestDTO(
                "Health", "lucide:heart", "seeded", ExperienceLevel.BEGINNER), userId);
        UUID categoryId = categoryService.getAllCategories(userId).get(0).id();

        habitService.createHabit(new CreateHabitDTO("Drink water", "seeded", "stay hydrated",
                "lucide:droplet", 3, 2, List.of(categoryId), ExperienceLevel.BEGINNER), userId);
        UUID habitId = habitService.getHabits(userId).get(0).id();

        taskService.createTask(new CreateTaskRequestDTO("Tidy the desk", "seeded",
                "lucide:broom", 2, 2, List.of(categoryId), false), userId);
        UUID taskId = taskService.getAllTasks(userId).get(0).id();

        diaryRoutineService.createDiaryRoutine(new DiaryRoutineRequestDTO(
                "Morning", "lucide:sun", RoutineType.DAILY, List.of(new RoutineSectionRequestDTO(
                        null, "Wake up", "lucide:sunrise", LocalTime.of(7, 0), LocalTime.of(8, 0),
                        List.of(new TaskGroupDTO(null, taskId, LocalTime.of(7, 30), LocalTime.of(7, 40), null)),
                        List.of(new HabitGroupDTO(null, habitId, LocalTime.of(7, 0), LocalTime.of(7, 10), null)),
                        false)), List.of()), userId);
        UUID routineId = diaryRoutineService.getAllDiaryRoutines(userId).get(0).id();

        scheduleService.create(new CreateScheduleDTO(Set.of(WeekDay.Monday), routineId), userId);
        chatService.createChat("A conversation with the agent", userId);

        // A goal under another one: the runbook deletes goals in one statement, and the
        // self-reference has to let it.
        UUID parentGoalId = createGoal("Run a half marathon", null, categoryId, userId);
        createGoal("Run 10 km", parentGoalId, categoryId, userId);

        UUID feedbackId = feedbackService.submitFeedback(new CreateFeedbackRequestDTO(
                FeedbackCategory.BUG, "the chart is empty", null), userId).id();
        feedbackReplyService.reply(feedbackId, userId,
                new CreateFeedbackReplyRequestDTO("it fixed itself"));

        // A deletion code someone asked for and never used. Normally the route spends it;
        // here nothing does, so it has to go with the account on its own.
        jdbc.update("INSERT INTO account_deletion_codes (id, user_id, code_hash, attempts, "
                + "created_at, expires_at) VALUES (?, ?, 'seeded', 0, now(), now())",
                UUID.randomUUID(), userId);

        // Mood, focus, the notebook, the briefing, linked sign-ins and the rest. The focus
        // rows point at the routine's habit group, which the runbook deletes in step 3, and
        // the notebook topic links to the habit and the category, which go in step 6.
        UUID itemGroupId = jdbc.queryForObject("SELECT hg.id FROM habit_groups hg "
                + "JOIN habits h ON h.id = hg.habit_id WHERE h.user_id = ?", UUID.class, userId);
        UserOwnedRows.seed(jdbc, userId, itemGroupId, routineId, habitId, categoryId);

        // Every table the guard below calls seeded really holds a row now, so the zero counts
        // after the runbook mean it removed them, not that they were never there.
        for (UserOwnedRows.UserForeignKey fk : UserOwnedRows.foreignKeysToUsers(jdbc)) {
            assertThat(count(fk.table(), fk.column(), userId))
                    .as("%s.%s holds nothing for this account, so the runbook proves nothing for it",
                            fk.table(), fk.column())
                    .isPositive();
        }

        long schedulesBefore = count("schedules");

        runRunbook(userId);

        assertThat(count("users", "id", userId)).isZero();
        for (UserOwnedRows.UserForeignKey fk : UserOwnedRows.foreignKeysToUsers(jdbc)) {
            assertThat(count(fk.table(), fk.column(), userId))
                    .as("%s.%s should be empty after the runbook", fk.table(), fk.column())
                    .isZero();
        }

        // The tables with no user_id, which are the ones a runbook gets wrong.
        assertThat(count("schedules"))
                .as("the schedule went with the routine")
                .isEqualTo(schedulesBefore - 1);
        assertThat(orphanedItemGroups()).as("no stranded inheritance parents").isZero();
        assertThat(orphanedBaseChecks()).as("no stranded inheritance parents").isZero();
    }

    /**
     * A table nobody seeds passes the loop above with nothing to delete. The schema says which
     * tables point at a user; every one of them gets a row before the runbook runs, or this
     * fails and names it.
     */
    @Test
    @DisplayName("every table that points at a user gets a row before the runbook runs")
    void everyTableThatPointsAtAUserIsSeededBeforeTheRunbook() {
        Set<String> unseeded = new TreeSet<>(UserOwnedRows.tablesPointingAtUsers(jdbc));
        unseeded.removeAll(UserOwnedRows.SEEDED);
        unseeded.removeAll(SEEDED_HERE);

        assertThat(unseeded)
                .as("These tables point at users but the runbook test never fills them, so "
                        + "it cannot tell whether the runbook clears them. Seed one in "
                        + "UserOwnedRows.seed and add the table to UserOwnedRows.SEEDED.")
                .isEmpty();
    }

    private UUID createGoal(String name, UUID parentId, UUID categoryId, UUID userId) {
        goalService.createGoal(new CreateGoalRequestDTO(
                name, null, "lucide:flag", 10.0, "km", 0.0, List.of(categoryId), null,
                LocalDate.now(ZoneOffset.UTC), LocalDate.now(ZoneOffset.UTC).plusMonths(2),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM, parentId), userId);
        return goalService.getAllGoals(userId).stream()
                .filter(goal -> goal.name().equals(name))
                .findFirst().orElseThrow().id();
    }

    /**
     * Runs the file the way an operator would, one statement at a time.
     *
     * Statements are split on blank lines rather than semicolons because several are
     * CTEs containing semicolon-free multi-line bodies, and a naive split on {@code ;}
     * would tear them in half. BEGIN/COMMIT are dropped: the test's own transaction
     * boundary stands in for them, and nesting one inside the other is not a thing
     * JDBC will do. Temp tables declared {@code ON COMMIT DROP} are recreated per run,
     * so the statement is rewritten to tolerate a connection that outlives it.
     */
    private void runRunbook(UUID userId) throws IOException {
        String sql = new String(new ClassPathResource("runbooks/manual-account-delete.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        Arrays.stream(sql.split("\\n\\s*\\n"))
                .map(this::stripComments)
                .map(String::trim)
                .filter(statement -> !statement.isEmpty())
                .filter(statement -> !statement.equalsIgnoreCase("BEGIN;")
                        && !statement.equalsIgnoreCase("COMMIT;"))
                .map(statement -> statement.replace(":userId", "'" + userId + "'"))
                .map(statement -> statement.replace("ON COMMIT DROP", ""))
                .forEach(statement -> jdbc.execute(
                        statement.startsWith("CREATE TEMP TABLE")
                                ? "DROP TABLE IF EXISTS doomed_schedules; " + statement
                                : statement));
    }

    private String stripComments(String block) {
        return Arrays.stream(block.split("\\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private long count(String table) {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return rows == null ? 0 : rows;
    }

    private long count(String table, String column, UUID userId) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, userId);
        return rows == null ? 0 : rows;
    }

    private long orphanedItemGroups() {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM item_groups g "
                + "WHERE NOT EXISTS (SELECT 1 FROM habit_groups h WHERE h.id = g.id) "
                + "AND NOT EXISTS (SELECT 1 FROM task_groups t WHERE t.id = g.id)", Long.class);
        return rows == null ? 0 : rows;
    }

    private long orphanedBaseChecks() {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM base_checks b "
                + "WHERE NOT EXISTS (SELECT 1 FROM habit_group_checks h WHERE h.id = b.id) "
                + "AND NOT EXISTS (SELECT 1 FROM task_group_checks t WHERE t.id = b.id)", Long.class);
        return rows == null ? 0 : rows;
    }
}
