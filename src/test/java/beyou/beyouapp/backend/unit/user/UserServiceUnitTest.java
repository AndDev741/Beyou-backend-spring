package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentService;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.PhotoStorageService;
import beyou.beyouapp.backend.user.PhotoUrlSigner;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import beyou.beyouapp.backend.user.dto.UserResponseDTO;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
public class UserServiceUnitTest {
    
    @Mock
    HttpServletResponse response;

    @Mock
    UserRepository userRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    TokenService tokenService;

    @Mock
    RefreshTokenService refreshTokenService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    PhotoStorageService photoStorageService;

    @Mock
    FeedbackAttachmentService feedbackAttachmentService;

    /**
     * R14/KTD11 — the scheduling half of the streak. Stubbed per test with the account's
     * frozen {@code USER} rows; left empty everywhere the schedule is beside the point,
     * which reads as "no day was ever scheduled" and so breaks nothing.
     */
    @Mock
    EntityCheckDayRepository entityCheckDayRepository;

    UserStreakService userStreakService;

    UserMapper userMapper;

    private UserService userService;

    User user = new User();
    UUID userId = UUID.randomUUID();

    /** Both only exist so deleteUser can clear the rows that block it. */
    @Mock
    private beyou.beyouapp.backend.security.passwordreset.PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private beyou.beyouapp.backend.domain.aiAgent.chat.ChatService chatService;


    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        MockitoAnnotations.openMocks(this);

        // The real walk over a mocked row store: these tests are about the streak rules,
        // so nothing between the service and the arithmetic is faked.
        userStreakService = new UserStreakService(entityCheckDayRepository);
        userMapper = new UserMapper(userStreakService, new PhotoUrlSigner("a-token-secret-for-tests", 720));

        user.setId(userId);
        user.setName("AndDev741");
        user.setEmail("myemail@gmail.com");
        user.setPerfilPhoto("url.com");
        user.setPerfilPhrase("life is good");
        user.setPerfilPhraseAuthor("lg?");
        user.setWidgetsIdInUse(List.of("widget4, widget5"));

        userService = new UserService(userRepository, passwordEncoder, tokenService, refreshTokenService, userMapper, photoStorageService, eventPublisher, feedbackAttachmentService, userStreakService, passwordResetTokenRepository, chatService);
    }

    /** One of the account's frozen day rows. */
    private EntityCheckDay userRow(LocalDate day, CheckDayOutcome outcome) {
        return new EntityCheckDay(user, CheckDayOwnerType.USER, userId, day, outcome);
    }

    /** Stubs the account's whole stored history — the only scheduling evidence the walk reads. */
    private void storedUserDays(EntityCheckDay... rows) {
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.USER, userId)).thenReturn(List.of(rows));
    }

    @Nested
    class AuthenticateTest {
        @Test
        public void shouldReturnAuthenticatedIfUserAreAuthenticated() {
            ResponseEntity<String> response = userService.verifyAuthentication();

            assertEquals(response.getBody(), "authenticated");
        }
    }

    @Nested
    class LoginAndRegister {
        @Test
        public void shouldRegisterANewUser() {
            UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "email1234@gmail.com",
                    "TestPassword1!");
            ResponseEntity<Map<String, String>> response = userService.registerUser(userRegisterDTO);

            assertEquals(ResponseEntity.ok().body(Map.of("success", "User registered successfully")),
                    response);
        }

        @Test
        public void shouldMakeLoginSuccessfully() throws Exception {
            UserLoginDTO userLoginDTO = new UserLoginDTO("testebeyou@gmail.com", "123456");
            User user = new User();
            user.setPassword("hashedPassword");
            user.setEmailVerified(true);

            when(userRepository.findByEmail(userLoginDTO.email())).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(userLoginDTO.password(), user.getPassword())).thenReturn(true);
            when(tokenService.generateJwtToken(user)).thenReturn("mockedToken");

            ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(null, response, userLoginDTO);

            UserResponseDTO userResponseDTO = userMapper.toResponseDTO(user);

            assertEquals(ResponseEntity.ok().body(Map.of("success", userResponseDTO)), loginResponse);
        }
    }

    @Nested
    class CrudOperations {
        @Test
        public void shouldGetAUserCorrectly() {
            String email = "testebeyou@gmail.com";
            Optional<User> getUser = userService.getUser(email);

            if (getUser.isPresent()) {
                User user = getUser.get();
                assertEquals("341627d3-bae7-4c14-871b-d876413e8a0a", user.getId().toString());
                assertEquals("aaa", user.getName());
                assertEquals("testebeyou@gmail.com", user.getEmail());
                assertEquals(false, user.isGoogleAccount());
            }

        }

        @Test
        public void shouldDeleteSuccessfullyAUser() {
            UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "newUser@gmail.com",
                    "TestPassword1!");
            userService.registerUser(userRegisterDTO);
            Optional<User> newUser = userService.getUser(userRegisterDTO.email());

            if (newUser.isPresent()) {
                ResponseEntity<Map<String, String>> response = userService.deleteUser(newUser.get());
                assertEquals(ResponseEntity.ok(Map.of("success", "User deleted successfully")),
                        response);
            }
        }

        @Test
        public void shouldEditTheUserInfoSuccessfully() {
            // Arrange
            UserEditDTO userEditDTO = new UserEditDTO(
                "new Name",
                "newphoto.com",
                "new PHRASE",
                "phrase author",
                List.of(),
                "light",
                ConstanceConfiguration.ANY,
                "en",
                null,
                null,
                null,
                null
                );

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(user)).thenReturn(user);
            // No uploaded photo file → response photo falls back to perfilPhoto.
            when(photoStorageService.getVersion(userId)).thenReturn(null);
            // ACT
            UserResponseDTO editedUser = userService.editUser(userEditDTO, userId);

            // Assert
            assertEquals(editedUser.name(), userEditDTO.name());
            assertEquals(editedUser.photo(), userEditDTO.photo());
            assertEquals(editedUser.phrase(), userEditDTO.phrase());
            assertEquals(editedUser.phrase_author(), userEditDTO.phrase_author());
        }

        @Test
        public void shouldEditTheWidgetsSuccessfully() {
            // Arrange
            UserEditDTO userEditDTO = new UserEditDTO(
                null,
                null,
                null,
                null,
                List.of("widget1E, widget2E"),
                null,
                ConstanceConfiguration.ANY,
                "en",
                null,
                null,
                null,
                null
            );

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(user)).thenReturn(user);
            // ACT

            UserResponseDTO editedUser = userService.editUser(userEditDTO, userId);

            // Assert
            assertEquals(editedUser.widgetsId(), userEditDTO.widgetsId());

        }
    }

    /**
     * R14 — the streak counts SCHEDULED days, not calendar-consecutive ones.
     *
     * <p>Completion still comes from {@code completedDays}; whether a day was scheduled
     * comes from the account's frozen {@code USER} rows, which {@code DayCloseService}
     * stamps {@code MISSED} when any routine covered the day and {@code NOT_SCHEDULED} /
     * {@code NOT_IN_ROUTINE} when none did (KTD11). A day is neutral — stepped over
     * without counting — unless its row says it was scheduled.
     */
    @Nested
    class ConstanceLogic {

        // A real Mon/Wed/Fri week, so "the intervening days" are actual Tuesdays.
        private static final LocalDate MON = LocalDate.of(2026, 8, 3);
        private static final LocalDate TUE = LocalDate.of(2026, 8, 4);
        private static final LocalDate WED = LocalDate.of(2026, 8, 5);
        private static final LocalDate THU = LocalDate.of(2026, 8, 6);
        private static final LocalDate FRI = LocalDate.of(2026, 8, 7);
        private static final LocalDate SAT = LocalDate.of(2026, 8, 8);
        private static final LocalDate SUN = LocalDate.of(2026, 8, 9);

        /** The rows a Mon/Wed/Fri routine leaves behind for a closed week. */
        private void monWedFriWeekIsClosed() {
            storedUserDays(
                    userRow(MON, CheckDayOutcome.MISSED),
                    userRow(TUE, CheckDayOutcome.NOT_SCHEDULED),
                    userRow(WED, CheckDayOutcome.MISSED),
                    userRow(THU, CheckDayOutcome.NOT_SCHEDULED),
                    userRow(FRI, CheckDayOutcome.MISSED),
                    userRow(SAT, CheckDayOutcome.NOT_SCHEDULED));
        }

        @Test
        public void shouldKeepTheStreakAcrossTheDaysNothingWasScheduled() {
            monWedFriWeekIsClosed();
            user.setCompletedDays(new HashSet<>(Set.of(MON, WED, FRI)));

            assertEquals(3, userStreakService.streakOf(user, FRI).currentStreak(),
                    "Tuesday and Thursday were never asked for, so they cost nothing");
        }

        @Test
        public void shouldBreakTheStreakOnlyOnADayThatWasActuallyScheduled() {
            monWedFriWeekIsClosed();
            // Wednesday was scheduled and left undone — the one thing that ends a run.
            user.setCompletedDays(new HashSet<>(Set.of(MON, FRI)));

            assertEquals(1, userStreakService.streakOf(user, FRI).currentStreak());
        }

        @Test
        public void shouldNotZeroTheStreakWhenReadDaysAfterTheLastCompletedDay() {
            // The removed `daysGap > 1` early return fired BEFORE the walk and returned 0
            // outright. Read on Sunday, two days past Friday, this was zero.
            monWedFriWeekIsClosed();
            user.setCompletedDays(new HashSet<>(Set.of(MON, WED, FRI)));

            assertEquals(3, userStreakService.streakOf(user, SUN).currentStreak());
        }

        @Test
        public void shouldTerminateWhenEveryGapDayIsUnscheduled() {
            // Nothing below the earliest completed day can end the walk, so the earliest
            // completed day is the floor. Without it this runs forever — on the login path
            // and inside the check transaction both.
            storedUserDays(
                    userRow(MON, CheckDayOutcome.NOT_SCHEDULED),
                    userRow(TUE, CheckDayOutcome.NOT_SCHEDULED),
                    userRow(WED, CheckDayOutcome.NOT_SCHEDULED),
                    userRow(THU, CheckDayOutcome.NOT_SCHEDULED),
                    userRow(FRI, CheckDayOutcome.NOT_SCHEDULED),
                    userRow(SAT, CheckDayOutcome.NOT_SCHEDULED));
            user.setCompletedDays(new HashSet<>(Set.of(MON)));

            assertTimeoutPreemptively(Duration.ofSeconds(2),
                    () -> assertEquals(1, userStreakService.streakOf(user, SUN).currentStreak()));
        }

        @Test
        public void shouldReportAStreakForAUserWithNoRoutinesAtAll() {
            // Every row reads NOT_IN_ROUTINE, and a day nothing could have been expected on
            // is not a day the user failed.
            storedUserDays(
                    userRow(MON, CheckDayOutcome.NOT_IN_ROUTINE),
                    userRow(TUE, CheckDayOutcome.NOT_IN_ROUTINE),
                    userRow(WED, CheckDayOutcome.NOT_IN_ROUTINE));
            user.setCompletedDays(new HashSet<>(Set.of(MON)));

            assertEquals(1, userStreakService.streakOf(user, THU).currentStreak());
        }

        @Test
        public void shouldStepOverADayWithNoRowAtAll() {
            // R18 — a night the day-close pass never ran leaves no row. Unknown is not
            // failed, or one outage would read back as a broken streak for everyone.
            storedUserDays(
                    userRow(MON, CheckDayOutcome.MISSED),
                    userRow(WED, CheckDayOutcome.MISSED));
            user.setCompletedDays(new HashSet<>(Set.of(MON, WED)));

            assertEquals(2, userStreakService.streakOf(user, WED).currentStreak());
        }

        @Test
        public void shouldReportDormantWhenNothingWasScheduledForFourteenDays() {
            // R20/KTD25 — the number stands; only the flag says the run has gone quiet.
            LocalDate lastScheduled = SUN.minusDays(14);
            storedUserDays(
                    userRow(lastScheduled, CheckDayOutcome.MISSED),
                    userRow(lastScheduled.plusDays(1), CheckDayOutcome.NOT_IN_ROUTINE),
                    userRow(SUN.minusDays(1), CheckDayOutcome.NOT_IN_ROUTINE));
            user.setCompletedDays(new HashSet<>(Set.of(lastScheduled)));

            var streak = userStreakService.streakOf(user, SUN);

            assertEquals(1, streak.currentStreak(), "Dormant flags the run, it does not erase it");
            assertTrue(streak.dormant());
        }

        @Test
        public void shouldNotReportDormantWhileSomethingWasScheduledInsideTheWindow() {
            LocalDate lastScheduled = SUN.minusDays(13);
            storedUserDays(
                    userRow(lastScheduled, CheckDayOutcome.MISSED),
                    userRow(SUN.minusDays(1), CheckDayOutcome.NOT_IN_ROUTINE));
            user.setCompletedDays(new HashSet<>(Set.of(lastScheduled)));

            var streak = userStreakService.streakOf(user, SUN);

            assertEquals(1, streak.currentStreak());
            assertFalse(streak.dormant(), "Thirteen days back is still inside the fourteen-day window");
        }

        @Test
        public void shouldCountTheDayCompleteInCompleteModeWhenTheOnlyItemWasSkipped() {
            // What makes a day complete is decided upstream against the user's
            // ConstanceConfiguration; markDayCompleted only records the verdict, and U6
            // did not change that. COMPLETE counts a skip as handled.
            user.setConstanceConfiguration(ConstanceConfiguration.COMPLETE);
            user.setCompletedDays(new HashSet<>());
            when(userRepository.save(user)).thenReturn(user);

            userService.markDayCompleted(user, WED);

            assertTrue(user.getCompletedDays().contains(WED));
            assertEquals(1, userStreakService.streakOf(user, WED).currentStreak());
        }

        @Test
        public void shouldCountTheDayCompleteInAnyModeWithOneItemOfThreeChecked() {
            user.setConstanceConfiguration(ConstanceConfiguration.ANY);
            user.setCompletedDays(new HashSet<>());
            when(userRepository.save(user)).thenReturn(user);

            userService.markDayCompleted(user, WED);

            assertTrue(user.getCompletedDays().contains(WED));
            assertEquals(1, userStreakService.streakOf(user, WED).currentStreak());
        }

        @Test
        public void shouldRaiseTheRecordWhenTheNewStreakBeatsIt() {
            monWedFriWeekIsClosed();
            user.setCompletedDays(new HashSet<>(Set.of(MON, WED)));
            user.setMaxConstance(2);
            when(userRepository.save(user)).thenReturn(user);

            userService.markDayCompleted(user, FRI);

            verify(userRepository, times(1)).save(user);
            assertEquals(3, userStreakService.streakOf(user, FRI).currentStreak());
            assertEquals(3, user.getMaxConstance());
        }

        @Test
        public void shouldNotLowerTheRecordWhenACompletedDayIsUnmarked() {
            // R13 — the record is a record. Undoing today's check drops the live streak and
            // leaves the best alone.
            monWedFriWeekIsClosed();
            user.setCompletedDays(new HashSet<>(Set.of(MON, WED, FRI)));
            user.setMaxConstance(3);
            when(userRepository.save(user)).thenReturn(user);

            userService.unmarkDayComplete(user, WED);

            verify(userRepository, times(1)).save(user);
            assertFalse(user.getCompletedDays().contains(WED));
            assertEquals(1, userStreakService.streakOf(user, FRI).currentStreak());
            assertEquals(3, user.getMaxConstance());
        }

        @Test
        public void shouldReturnZeroWhenNoCompletedDaysExist() {
            user.setCompletedDays(new HashSet<>());

            var streak = userStreakService.streakOf(user, SUN);

            assertEquals(0, streak.currentStreak());
            assertFalse(streak.dormant(), "Nothing to be dormant about at zero");
            // The row store is never even asked: a fresh account pays no query on login.
            verifyNoInteractions(entityCheckDayRepository);
        }

        @Test
        public void shouldReportTheNewStreakImmediatelyAfterAChecksTransaction() {
            // The check response has to carry the run the check just produced, with no
            // day-close run in between. markDayCompleted adds the day and reads it back in
            // the same call, off the same in-memory user.
            monWedFriWeekIsClosed();
            user.setCompletedDays(new HashSet<>(Set.of(MON, WED)));
            user.setMaxConstance(0);
            when(userRepository.save(user)).thenReturn(user);

            userService.markDayCompleted(user, FRI);

            assertEquals(3, user.getMaxConstance(),
                    "The record was raised from the streak computed inside the same call");
        }
    }

    @Nested
    class Exceptions {
        @Test
        public void shouldThrowEmailAlreadyInUseError() {
            UserRegisterDTO userRegisterDTO = new UserRegisterDTO("Name", "email@gmail.com",
                    "TestPassword1!");
            User user = new User(userRegisterDTO);
            when(userRepository.findByEmail(userRegisterDTO.email())).thenReturn(Optional.of(user));

            ResponseEntity<Map<String, String>> response = userService.registerUser(userRegisterDTO);

            assertEquals(ResponseEntity.badRequest().body(Map.of("error", "Email already in use")),
                    response);
        }

        @Test
        public void shouldThrowExceptionForRequiredName() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("     ", "email@gmail.com",
                        "TestPassword1!");
                userService.registerUser(newUser);
            });

            assertEquals("Name is Required", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForMinimumCharactersInName() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("N", "email@gmail.com",
                        "TestPassword1!");
                userService.registerUser(newUser);
            });

            assertEquals("Name require a minimum of 2 characters", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForRequiredEmail() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("Name", "",
                        "TestPassword1!");
                userService.registerUser(newUser);
            });

            assertEquals("Email is Required", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForInvalidEmail() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("Name", "email",
                        "TestPassword1!");
                userService.registerUser(newUser);
            });

            assertEquals("Email is invalid", exception.getMessage());
        }

        @Test
        public void shouldThrowExceptionForRequiredPassword() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("Name", "email@gmail.com",
                        "   ");
                userService.registerUser(newUser);
            });

            // A blank password (whitespace) fails both @NotBlank and @Size; Hibernate Validator
            // does not guarantee which violation message is reported first, so accept either.
            String message = exception.getMessage();
            assertTrue(
                message.equals("Password is Required") ||
                message.equals("Password require a minimum of 12 characters"),
                "Expected password validation error, got: " + message);
        }

        @Test
        public void shouldThrowExceptionForMinimumCharacterInPassword() {
            Exception exception = assertThrows(IllegalArgumentException.class, () -> {
                UserRegisterDTO newUser = new UserRegisterDTO("Name", "email@gmail.com",
                        "12345");
                userService.registerUser(newUser);
            });

            assertEquals("Password require a minimum of 12 characters", exception.getMessage());
        }

        @Test
        public void shouldReturnIncorrectEmailOrPasswordByPassingWrongEmail() throws Exception {
            UserLoginDTO userLoginDTO = new UserLoginDTO("incorrect@gmail.com", "123456");

            when(userRepository.findByEmail(userLoginDTO.email())).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(null, response, userLoginDTO);

            assertEquals(ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "Email or password incorrect")), loginResponse);
        }

        @Test
        public void shouldReturnIncorrectEmailOrPasswordByPassingWrongPassword() throws Exception {
            UserLoginDTO userLoginDTO = new UserLoginDTO("testebeyou@gmail.com", "313213213");
            User user = new User();
            user.setPassword("hashedPassword");

            when(userRepository.findByEmail(userLoginDTO.email())).thenReturn(Optional.empty());
            when(passwordEncoder.matches(userLoginDTO.password(), user.getPassword())).thenReturn(false);

            ResponseEntity<Map<String, Object>> loginResponse = userService.doLogin(null, response, userLoginDTO);

            assertEquals(ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(Map.of("error", "Email or password incorrect")), loginResponse);
        }
    }
}
