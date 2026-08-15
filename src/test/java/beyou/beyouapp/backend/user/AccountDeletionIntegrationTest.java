package beyou.beyouapp.backend.user;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.chat.ChatService;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
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
 * a green run means "the delete button works for someone who has used BeYou",
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
    @Autowired ChatService chatService;
    @Autowired beyou.beyouapp.backend.user.deletion.AccountDeletionService accountDeletionService;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;

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
                "Morning", "lucide:sun", List.of(new RoutineSectionRequestDTO(
                        null, "Wake up", "lucide:sunrise", LocalTime.of(7, 0), LocalTime.of(8, 0),
                        List.of(new TaskGroupDTO(null, taskId, LocalTime.of(7, 30), LocalTime.of(7, 40), null)),
                        List.of(new HabitGroupDTO(null, habitId, LocalTime.of(7, 0), LocalTime.of(7, 10), null)),
                        false))), userId);

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

        assertThatCode(() -> accountDeletionService.confirm(user, code))
                .as("a used account must be deletable through the route people will use")
                .doesNotThrowAnyException();

        assertThat(rowsFor("users", "id", userId)).isZero();
        assertThat(rowsFor("chats", "user_id", userId)).isZero();
        assertThat(rowsFor("password_reset_tokens", "user_id", userId)).isZero();
        assertThat(rowsFor("tasks", "user_id", userId)).isZero();
        assertThat(rowsFor("habits", "user_id", userId)).isZero();
        assertThat(rowsFor("categories", "user_id", userId)).isZero();
        assertThat(rowsFor("routines", "user_id", userId)).isZero();
        assertThat(rowsFor("refresh_tokens", "user_id", userId)).isZero();
        assertThat(rowsFor("account_deletion_codes", "user_id", userId)).isZero();
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
}
