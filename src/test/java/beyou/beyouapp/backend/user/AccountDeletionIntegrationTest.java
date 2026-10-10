package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.routine.RoutineType;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.security.passwordreset.PasswordResetToken;
import beyou.beyouapp.backend.security.passwordreset.PasswordResetTokenRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static beyou.beyouapp.backend.user.deletion.AccountDeletionService.MAX_ATTEMPTS;

/**
 * The account a real person would be deleting.
 *
 * {@code UserDeletionCommitBoundaryIntegrationTest} pins WHEN the attachment purge
 * runs; this pins WHETHER the delete can happen at all for an account that has
 * actually been used. That question has a history: the method was written long
 * before a route existed, so nothing had ever asked it to delete an account
 * carrying a chat, a reset token or a task — and each of those reaches the users
 * row through a plain foreign key with no cascade behind it.
 *
 * Every seeded row here is one the app creates on its own during ordinary use, so
 * a green run means "the delete button works for someone who has used Beyou",
 * which is the only version of it worth shipping.
 */
@org.springframework.test.context.TestPropertySource(properties = "e2e.expose-deletion-code=true")
class AccountDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "deletion-integration@beyou.test";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CategoryService categoryService;
    @Autowired HabitService habitService;
    @Autowired TaskService taskService;
    @Autowired DiaryRoutineService diaryRoutineService;
    @Autowired beyou.beyouapp.backend.domain.routine.schedule.ScheduleService scheduleService;
    @Autowired ChatService chatService;
    @Autowired beyou.beyouapp.backend.user.deletion.AccountDeletionService accountDeletionService;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired GoalService goalService;
    @Autowired FeedbackService feedbackService;
    @Autowired FeedbackReplyService feedbackReplyService;
    @Autowired JdbcTemplate jdbc;

    /**
     * The user-owned tables the test below fills through the application's own services. The
     * rest come from {@link UserOwnedRows}. A table in neither fails
     * {@link #everyTableThatPointsAtAUserIsSeededBeforeTheDelete()}.
     */
    private static final Set<String> SEEDED_THROUGH_SERVICES = Set.of(
            "categories", "habits", "tasks", "goals", "routines", "chats",
            "password_reset_tokens", "account_deletion_codes", "feedback", "feedback_reply");

    /** Nothing here should try to reach an SMTP server. */
    @MockitoBean EmailService emailService;

    @Value("${spring.datasource.url}") String jdbcUrl;
    @Value("${spring.datasource.username}") String jdbcUsername;
    @Value("${spring.datasource.password}") String jdbcPassword;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));

        User fresh = new User();
        fresh.setName("someone leaving");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);
    }

    @Test
    @DisplayName("an account that has actually been used can still be deleted")
    void deletesAnAccountThatCarriesEveryKindOfRow() {
        UUID userId = user.getId();
        // A delta, not an absolute. Orphans have no user to scope them by, so an
        // absolute zero would make this file fail for whatever another test in the
        // shared database left behind.
        int orphansBefore = orphanedSchedules();

        categoryService.createCategory(new CategoryRequestDTO(
                "Health", "lucide:heart", "seeded", ExperienceLevel.BEGINNER), userId);
        UUID categoryId = categoryService.getAllCategories(userId).get(0).id();

        habitService.createHabit(new CreateHabitDTO("Drink water", "seeded", "stay hydrated",
                "lucide:droplet", 3, 2, List.of(categoryId), ExperienceLevel.BEGINNER), userId);
        UUID habitId = habitService.getHabits(userId).get(0).id();

        taskService.createTask(new CreateTaskRequestDTO("Tidy the desk", "seeded",
                "lucide:broom", 2, 2, List.of(categoryId), false), userId);
        UUID taskId = taskService.getAllTasks(userId).get(0).id();

        // A routine holding both, which is what makes tasks and habits reachable
        // from a second direction (task_groups / habit_groups).
        diaryRoutineService.createDiaryRoutine(new DiaryRoutineRequestDTO(
                "Morning", "lucide:sun", RoutineType.DAILY, List.of(new RoutineSectionRequestDTO(
                        null, "Wake up", "lucide:sunrise", LocalTime.of(7, 0), LocalTime.of(8, 0),
                        List.of(new TaskGroupDTO(null, taskId, LocalTime.of(7, 30), LocalTime.of(7, 40), null)),
                        List.of(new HabitGroupDTO(null, habitId, LocalTime.of(7, 0), LocalTime.of(7, 10), null)),
                        false)), List.of()), userId);

        // Scheduled, because an unscheduled routine cannot leave a schedule behind.
        UUID routineId = diaryRoutineService.getAllDiaryRoutines(userId).get(0).id();
        scheduleService.create(new CreateScheduleDTO(
                Set.of(WeekDay.Monday, WeekDay.Wednesday), routineId), userId);

        // A goal with a sub-goal: goals.parent_id points back at goals, so the delete also has
        // to get past a foreign key inside the same table.
        UUID parentGoalId = createGoal("Run a half marathon", null, categoryId, userId);
        createGoal("Run 10 km", parentGoalId, categoryId, userId);

        // Feedback with a reply on it. The rows cascade in the database; the reply's author
        // column nulls instead.
        UUID feedbackId = feedbackService.submitFeedback(new CreateFeedbackRequestDTO(
                FeedbackCategory.BUG, "the chart is empty", null), userId).id();
        feedbackReplyService.reply(feedbackId, userId,
                new CreateFeedbackReplyRequestDTO("it fixed itself"));

        // Everything added since: mood, focus, the notebook, the briefing, linked sign-ins and
        // the rest. Their migrations say they cascade; this is what makes that a fact.
        UUID itemGroupId = jdbc.queryForObject("SELECT hg.id FROM habit_groups hg "
                + "JOIN habits h ON h.id = hg.habit_id WHERE h.user_id = ?", UUID.class, userId);
        UserOwnedRows.seed(jdbc, userId, itemGroupId, routineId, habitId, categoryId);

        // The two plain foreign keys that used to block the delete outright.
        chatService.createChat("A conversation with the agent", userId);
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash("a reset that was asked for once");
        token.setCreatedAt(Timestamp.from(Instant.now()));
        token.setExpiresAt(Timestamp.from(Instant.now().plusSeconds(900)));
        passwordResetTokenRepository.saveAndFlush(token);

        // Through the real flow, not straight to deleteUser: asking for a code leaves
        // a row of its own pointing at the account, and spending it leaves that row
        // managed in the session. Calling deleteUser directly skips both, which is
        // why the first version of this test passed while the route 500'd.
        String code = accountDeletionService.requestCode(user);
        assertThat(code).as("the property above must expose the code").isNotNull();

        // Every table the guard below calls seeded really holds a row now, so the zero counts
        // after the delete mean the rows went, not that they were never there.
        for (UserOwnedRows.UserForeignKey fk : UserOwnedRows.foreignKeysToUsers(jdbc)) {
            assertThat(rowsFor(fk.table(), fk.column(), userId))
                    .as("%s.%s holds nothing for this account, so its delete proves nothing",
                            fk.table(), fk.column())
                    .isPositive();
        }

        assertThatCode(() -> accountDeletionService.confirm(user, code))
                .as("a used account must be deletable through the route people will use")
                .doesNotThrowAnyException();

        assertThat(rowsFor("users", "id", userId)).isZero();
        // Every column in the schema that points at a user, read from the schema itself, so a
        // table added next year is checked here without anyone editing this line.
        for (UserOwnedRows.UserForeignKey fk : UserOwnedRows.foreignKeysToUsers(jdbc)) {
            assertThat(rowsFor(fk.table(), fk.column(), userId))
                    .as("%s.%s still names the deleted account", fk.table(), fk.column())
                    .isZero();
        }

        // The row nothing counts by user_id, because it has no user_id to count by.
        // A schedule is reachable only through routines.schedule_id, so once the
        // routine is gone an orphan is invisible to every check above — which is how
        // this leaked unnoticed until a dev database was queried by hand after a real
        // deletion. ScheduleLifecycleIntegrationTest pins the other two ways in.
        assertThat(orphanedSchedules())
                .as("a deleted account must not leave a schedule nobody can reach")
                .isEqualTo(orphansBefore);
    }


    /**
     * The guard for the next table.
     *
     * <p>The loop above only proves something for tables that held a row before the delete.
     * A table nobody seeds passes it with zero rows before and zero after. So the list of
     * tables pointing at {@code users} is read from the live schema, and every one has to be
     * seeded somewhere in this class. Add a user-owned table and this fails until a row for it
     * goes into {@link UserOwnedRows} or into the services above.
     */
    @Test
    @DisplayName("every table that points at a user gets a row before the delete runs")
    void everyTableThatPointsAtAUserIsSeededBeforeTheDelete() {
        Set<String> unseeded = new TreeSet<>(UserOwnedRows.tablesPointingAtUsers(jdbc));
        unseeded.removeAll(UserOwnedRows.SEEDED);
        unseeded.removeAll(SEEDED_THROUGH_SERVICES);

        assertThat(unseeded)
                .as("These tables point at users but nothing in this test puts a row in them, "
                        + "so it cannot tell whether they go with the account. Seed one in "
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
     * The cap on guessing, against a real transaction boundary.
     *
     * A wrong code is refused by throwing, and the throw rolls its transaction back —
     * so an increment written inside it never survived. That left `attempts` at zero
     * forever and made MAX_ATTEMPTS dead code, with nothing but the generic write
     * bucket between a six-digit space and a walk through it. The unit test could not
     * see it (a mock repository has no transaction to roll back) and the first version
     * of this test never sent a wrong code at all.
     */
    @Test
    @DisplayName("a wrong code is counted across requests, and the sixth one closes the code")
    void wrongCodesAreCountedAndEventuallyCloseTheCode() {
        String code = accountDeletionService.requestCode(user);
        UUID codeId = codeIdFor(user.getId());

        assertThatThrownBy(() -> accountDeletionService.confirm(user, "000000"))
                .isInstanceOf(BusinessException.class);

        assertThat(attemptsOnAnIndependentConnection(codeId))
                .as("the count has to outlive the transaction the refusal rolls back")
                .isEqualTo(1);

        for (int guess = 2; guess <= MAX_ATTEMPTS; guess++) {
            assertThatThrownBy(() -> accountDeletionService.confirm(user, "000000"))
                    .isInstanceOf(BusinessException.class);
        }
        assertThat(attemptsOnAnIndependentConnection(codeId)).isEqualTo(MAX_ATTEMPTS);

        // The sixth try is refused for a different reason, and from here even the real
        // code is worthless: the way back is a new code, which the account's inbox has
        // by then been told about five times.
        assertThatThrownBy(() -> accountDeletionService.confirm(user, "000000"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorKey())
                        .isEqualTo(ErrorKey.DELETION_CODE_TOO_MANY_ATTEMPTS));

        assertThatThrownBy(() -> accountDeletionService.confirm(user, code))
                .as("a code that has been walked at is spent, right digits or not")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorKey())
                        .isEqualTo(ErrorKey.DELETION_CODE_TOO_MANY_ATTEMPTS));

        assertThat(rowsFor("users", "id", user.getId()))
                .as("and none of that deleted anything")
                .isEqualTo(1);
    }

    private UUID codeIdFor(UUID userId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM account_deletion_codes WHERE user_id = ? ORDER BY created_at DESC LIMIT 1")) {
            statement.setObject(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the deletion code row", e);
        }
    }

    private int attemptsOnAnIndependentConnection(UUID codeId) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT attempts FROM account_deletion_codes WHERE id = ?")) {
            statement.setObject(1, codeId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the attempt count", e);
        }
    }

    /**
     * Read from outside the application's pool and outside the test's transaction,
     * so what is asserted is what a fresh connection can see.
     */
    private int rowsFor(String table, String column, UUID userId) {
        String sql = "SELECT count(*) FROM " + table + " WHERE " + column + " = ?";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count rows in " + table, e);
        }
    }

    /**
     * Schedules with no routine pointing at them, counted on a connection of its own.
     *
     * A schedule row is an id and nothing more, so once its routine is gone there is no
     * column left to identify it by — not a user, not a name. The only question that can
     * still be asked is whether anything references it at all.
     */
    private int orphanedSchedules() {
        String sql = "SELECT count(*) FROM schedules s "
                + "WHERE NOT EXISTS (SELECT 1 FROM routines r WHERE r.schedule_id = s.id)";
        try (Connection connection = DriverManager.getConnection(jdbcUrl, jdbcUsername, jdbcPassword);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("could not count orphaned schedules", e);
        }
    }
}
