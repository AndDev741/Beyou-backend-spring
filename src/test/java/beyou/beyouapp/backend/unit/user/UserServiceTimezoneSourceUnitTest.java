package beyou.beyouapp.backend.unit.user;

import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentService;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.PhotoStorageService;
import beyou.beyouapp.backend.user.PhotoUrlSigner;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.dto.UserEditDTO;
import beyou.beyouapp.backend.user.enums.TimezoneSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * The adoption policy on {@code users.timezone}, which is the whole reason
 * {@link TimezoneSource} exists.
 *
 * <p>The rule these pin: a zone the CLIENT detected may only overwrite an account that has
 * never had a real answer. A zone a PERSON picked always wins and is permanent. Getting
 * this backwards in either direction is a real failure — too permissive and a laptop opened
 * abroad silently moves a travelling user's day boundary, too strict and the accounts that
 * predate signup-time detection stay on UTC forever.
 */
@ExtendWith(SpringExtension.class)
class UserServiceTimezoneSourceUnitTest {

    @Mock UserRepository userRepository;
    @Mock org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock TokenService tokenService;
    @Mock RefreshTokenService refreshTokenService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PhotoStorageService photoStorageService;
    @Mock FeedbackAttachmentService feedbackAttachmentService;
    @Mock EntityCheckDayRepository entityCheckDayRepository;
    @Mock beyou.beyouapp.backend.security.passwordreset.PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock beyou.beyouapp.backend.domain.aiAgent.chat.ChatService chatService;

    private UserService userService;
    private User user;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        UserStreakService userStreakService = new UserStreakService(entityCheckDayRepository);
        UserMapper userMapper = new UserMapper(
                userStreakService, new PhotoUrlSigner("a-token-secret-for-tests", 720));

        user = new User();
        user.setId(userId);
        user.setName("Ana");
        user.setEmail("ana@example.com");
        user.setTimezone("UTC");
        user.setTimezoneSource(TimezoneSource.DEFAULT);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        when(photoStorageService.getVersion(userId)).thenReturn(null);
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                eq(CheckDayOwnerType.USER), eq(userId))).thenReturn(List.of());

        userService = new UserService(userRepository, passwordEncoder, tokenService, refreshTokenService,
                userMapper, photoStorageService, eventPublisher, feedbackAttachmentService,
                userStreakService, passwordResetTokenRepository,
                mock(beyou.beyouapp.backend.user.verification.EmailVerificationService.class), chatService);
    }

    /** Everything null but the two fields under test — PATCH semantics, so nothing else moves. */
    private static UserEditDTO timezoneEdit(String timezone, TimezoneSource source) {
        return new UserEditDTO(null, null, null, null, null, null, null, null, null,
                timezone, source, null);
    }

    @Test
    @DisplayName("a picked zone carries no source and is stamped EXPLICIT")
    void pickedZoneIsExplicit() {
        userService.editUser(timezoneEdit("Europe/Lisbon", null), userId);

        assertEquals("Europe/Lisbon", user.getTimezone());
        assertEquals(TimezoneSource.EXPLICIT, user.getTimezoneSource());
    }

    @Test
    @DisplayName("a detected zone adopts over an account that never answered")
    void detectedZoneAdoptsOverDefault() {
        userService.editUser(timezoneEdit("Europe/Lisbon", TimezoneSource.DETECTED), userId);

        assertEquals("Europe/Lisbon", user.getTimezone());
        assertEquals(TimezoneSource.DETECTED, user.getTimezoneSource());
    }

    @Test
    @DisplayName("a detected zone does NOT overwrite a person's pick")
    void detectedZoneLeavesExplicitAlone() {
        user.setTimezone("UTC");
        user.setTimezoneSource(TimezoneSource.EXPLICIT);

        userService.editUser(timezoneEdit("Europe/Lisbon", TimezoneSource.DETECTED), userId);

        // Someone deliberately chose UTC. A browser reporting otherwise is not evidence
        // they changed their mind, and adopting here would move every day boundary this
        // account has ever written against.
        assertEquals("UTC", user.getTimezone());
        assertEquals(TimezoneSource.EXPLICIT, user.getTimezoneSource());
    }

    @Test
    @DisplayName("a detected zone does not re-adopt over an already-detected one")
    void detectedZoneDoesNotReadoptOverDetected() {
        user.setTimezone("Europe/Lisbon");
        user.setTimezoneSource(TimezoneSource.DETECTED);

        userService.editUser(timezoneEdit("America/Sao_Paulo", TimezoneSource.DETECTED), userId);

        // The travelling case. The UI surfaces this as a suggestion; the server does not
        // decide it.
        assertEquals("Europe/Lisbon", user.getTimezone());
        assertEquals(TimezoneSource.DETECTED, user.getTimezoneSource());
    }

    @Test
    @DisplayName("a person can always overwrite a detected zone")
    void explicitPickBeatsDetected() {
        user.setTimezone("Europe/Lisbon");
        user.setTimezoneSource(TimezoneSource.DETECTED);

        userService.editUser(timezoneEdit("America/Sao_Paulo", null), userId);

        assertEquals("America/Sao_Paulo", user.getTimezone());
        assertEquals(TimezoneSource.EXPLICIT, user.getTimezoneSource());
    }

    @Test
    @DisplayName("no client may reset an account to DEFAULT")
    void clientCannotSendDefault() {
        BusinessException thrown = assertThrows(BusinessException.class,
                () -> userService.editUser(timezoneEdit("Europe/Lisbon", TimezoneSource.DEFAULT), userId));

        assertEquals(ErrorKey.INVALID_REQUEST, thrown.getErrorKey());
        assertEquals("UTC", user.getTimezone());
        assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
    }

    @Test
    @DisplayName("an unknown zone still throws, and changes nothing")
    void unknownZoneThrows() {
        BusinessException thrown = assertThrows(BusinessException.class,
                () -> userService.editUser(timezoneEdit("Mars/Olympus", null), userId));

        assertEquals(ErrorKey.INVALID_REQUEST, thrown.getErrorKey());
        assertEquals("UTC", user.getTimezone());
        assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
    }

    @Test
    @DisplayName("an edit that carries no timezone leaves both fields alone")
    void editWithoutTimezoneIsInert() {
        UserEditDTO nameOnly = new UserEditDTO("Ana Maria", null, null, null, null, null, null,
                null, null, null, null, null);

        userService.editUser(nameOnly, userId);

        assertEquals("Ana Maria", user.getName());
        assertEquals("UTC", user.getTimezone());
        assertEquals(TimezoneSource.DEFAULT, user.getTimezoneSource());
    }

    @Test
    @DisplayName("the source travels back out on the response, so clients need no heuristic")
    void responseCarriesTheSource() {
        var response = userService.editUser(timezoneEdit("Europe/Lisbon", TimezoneSource.DETECTED), userId);

        assertEquals("Europe/Lisbon", response.timezone());
        assertEquals(TimezoneSource.DETECTED, response.timezoneSource());
    }
}
