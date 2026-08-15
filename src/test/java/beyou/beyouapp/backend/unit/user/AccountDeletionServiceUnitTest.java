package beyou.beyouapp.backend.unit.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.deletion.AccountDeletionCode;
import beyou.beyouapp.backend.user.deletion.AccountDeletionCodeRepository;
import beyou.beyouapp.backend.user.deletion.AccountDeletionService;

/**
 * Deleting an account is the one action with nothing behind it, so the code that
 * unlocks it carries the weight: it is mailed rather than shown, it dies of age,
 * it is single-use, and it does not survive being guessed at.
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceUnitTest {

    @Mock AccountDeletionCodeRepository codeRepository;
    @Mock EmailService emailService;
    @Mock UserService userService;

    /** The real encoder: what is stored must not be the code itself. */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @InjectMocks AccountDeletionService service;

    private final User user = new User();

    @BeforeEach
    void setUp() {
        user.setId(UUID.randomUUID());
        user.setEmail("leaving@beyou.test");
        user.setLanguageInUse("pt");
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "codeTtlMinutes", 15L);
        ReflectionTestUtils.setField(service, "cooldownSeconds", 60L);
    }

    private AccountDeletionCode storedCode(String raw, Instant expiresAt, int attempts, Timestamp usedAt) {
        AccountDeletionCode code = new AccountDeletionCode();
        code.setId(UUID.randomUUID());
        code.setUser(user);
        code.setCodeHash(passwordEncoder.encode(raw));
        code.setAttempts(attempts);
        code.setCreatedAt(Timestamp.from(Instant.now().minusSeconds(120)));
        code.setExpiresAt(Timestamp.from(expiresAt));
        code.setUsedAt(usedAt);
        return code;
    }

    @Test
    void requestMailsSixDigitsAndStoresOnlyTheirHash() {
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.empty());

        service.requestCode(user);

        ArgumentCaptor<AccountDeletionCode> saved = ArgumentCaptor.forClass(AccountDeletionCode.class);
        verify(codeRepository).save(saved.capture());
        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAccountDeletionCodeEmail(eq("leaving@beyou.test"), mailed.capture(),
                eq(Duration.ofMinutes(15)), eq("pt"));

        assertTrue(mailed.getValue().matches("\\d{6}"), mailed.getValue());
        assertNotNull(saved.getValue().getCodeHash());
        assertTrue(passwordEncoder.matches(mailed.getValue(), saved.getValue().getCodeHash()));
        // The code itself is never stored, only something that can recognise it.
        assertTrue(!saved.getValue().getCodeHash().contains(mailed.getValue()));
        // Any code from before this one stops working.
        verify(codeRepository).invalidateActiveCodes(eq(user.getId()), any(), any());
    }

    @Test
    void requestRefusesWhileTheLastCodeIsStillFresh() {
        AccountDeletionCode recent = storedCode("123456", Instant.now().plusSeconds(900), 0, null);
        recent.setCreatedAt(Timestamp.from(Instant.now()));
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(recent));

        BusinessException error = assertThrows(BusinessException.class, () -> service.requestCode(user));

        assertEquals(ErrorKey.DELETION_CODE_TOO_MANY_REQUESTS, error.getErrorKey());
        verify(emailService, never()).sendAccountDeletionCodeEmail(anyString(), anyString(), any(), anyString());
    }

    @Test
    void theRightCodeDeletesTheAccountAndSpendsItself() {
        AccountDeletionCode code = storedCode("123456", Instant.now().plusSeconds(900), 0, null);
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(code));
        when(userService.deleteUser(user)).thenReturn(ResponseEntity.ok(Map.of("success", "User deleted successfully")));

        ResponseEntity<Map<String, String>> response = service.confirm(user, "123456");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(code.getUsedAt(), "a spent code must not work twice");
        verify(userService).deleteUser(user);
    }

    @Test
    void aWrongCodeCostsAnAttemptAndDeletesNothing() {
        AccountDeletionCode code = storedCode("123456", Instant.now().plusSeconds(900), 0, null);
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(code));

        BusinessException error = assertThrows(BusinessException.class, () -> service.confirm(user, "000000"));

        assertEquals(ErrorKey.DELETION_CODE_INVALID, error.getErrorKey());
        assertEquals(1, code.getAttempts());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    void aGuessedAtCodeStopsAnsweringBeforeTheMillionthTry() {
        AccountDeletionCode code = storedCode("123456", Instant.now().plusSeconds(900), 5, null);
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(code));

        BusinessException error = assertThrows(BusinessException.class, () -> service.confirm(user, "123456"));

        assertEquals(ErrorKey.DELETION_CODE_TOO_MANY_ATTEMPTS, error.getErrorKey());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    void anExpiredCodeIsRefused() {
        AccountDeletionCode code = storedCode("123456", Instant.now().minusSeconds(1), 0, null);
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(code));

        BusinessException error = assertThrows(BusinessException.class, () -> service.confirm(user, "123456"));

        assertEquals(ErrorKey.DELETION_CODE_EXPIRED, error.getErrorKey());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    void aSpentCodeIsRefused() {
        AccountDeletionCode code = storedCode("123456", Instant.now().plusSeconds(900), 0,
                Timestamp.from(Instant.now().minusSeconds(5)));
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.of(code));

        BusinessException error = assertThrows(BusinessException.class, () -> service.confirm(user, "123456"));

        assertEquals(ErrorKey.DELETION_CODE_INVALID, error.getErrorKey());
        verify(userService, never()).deleteUser(any());
    }

    @Test
    void confirmingWithoutEverAskingForACodeIsRefused() {
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.empty());

        BusinessException error = assertThrows(BusinessException.class, () -> service.confirm(user, "123456"));

        assertEquals(ErrorKey.DELETION_CODE_INVALID, error.getErrorKey());
        verify(userService, never()).deleteUser(any());
    }

    /**
     * A code the caller already holds is not lost when the mail fails. Only the e2e
     * profile hands it back, and there is no SMTP there, so cleaning up on a failed
     * send deleted the row for a code the test had in its hand and made the flow
     * impossible to finish.
     */
    @Test
    void anExposedCodeSurvivesAFailedEmail() {
        ReflectionTestUtils.setField(service, "exposeCode", true);
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("no SMTP here"))
                .when(emailService).sendAccountDeletionCodeEmail(anyString(), anyString(), any(), anyString());

        String returned = service.requestCode(user);

        assertTrue(returned.matches("\\d{6}"), returned);
        verify(codeRepository, never()).deleteById(any());
    }

    @Test
    void anUnsentCodeIsCleanedUpWhenNobodyGotIt() {
        when(codeRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("mail server down"))
                .when(emailService).sendAccountDeletionCodeEmail(anyString(), anyString(), any(), anyString());

        assertEquals(null, service.requestCode(user));

        // Nobody received it, so holding the cooldown against the user would be cruel.
        verify(codeRepository).deleteById(any());
    }
}
