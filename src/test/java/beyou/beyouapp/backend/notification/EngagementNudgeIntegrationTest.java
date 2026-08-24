package beyou.beyouapp.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.notification.engagement.EngagementNudgeService;
import beyou.beyouapp.backend.notification.engagement.NotificationSendRepository;
import beyou.beyouapp.backend.notification.engagement.NudgeKind;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The rules that stand between a trigger firing and an e-mail arriving.
 *
 * <p>{@code NudgeEligibilityUnitTest} covers whether an account has earned a nudge. This
 * covers whether it may actually be sent one: the preference, the unverified address, the
 * gap between mails, the daily budget, and the ordering of the send against the row that
 * records it. Every one of those is a reason to send nothing, and each is worth more than
 * the trigger it suppresses.
 */
@Transactional
@TestPropertySource(properties = {
        "engagement.daily-cap=2",
        "engagement.min-days-between=3",
        "engagement.min-streak-to-defend=3",
        "engagement.record-gap=2"
})
class EngagementNudgeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    EngagementNudgeService nudgeService;

    @Autowired
    NotificationSendRepository sendRepository;

    @Autowired
    NotificationPreferencesService preferencesService;

    @Autowired
    EntityCheckDayRepository checkDayRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EntityManager entityManager;

    /**
     * Mocked so no test here speaks SMTP, and so the send can be made to fail on demand —
     * which is the only way to assert the ordering the service depends on.
     */
    @MockitoBean
    EmailService emailService;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    /** The day that falls out of the 7-day window after TODAY. */
    private static final LocalDate EXPIRING = TODAY.minusDays(7);

    private User user;

    @BeforeEach
    void setUp() {
        reset(emailService);
        user = verifiedUser();
    }

    private User verifiedUser() {
        User created = new User();
        created.setName("Nudge Target");
        created.setEmail("nudge-" + UUID.randomUUID() + "@test.com");
        created.setPassword("irrelevant");
        created.setEmailVerified(true);
        created.setCompletedDays(new HashSet<>());
        created.setMaxConstance(0);
        return userRepository.save(created);
    }

    /** Gives the account a missed day that expires after TODAY, so a nudge is earned. */
    private void giveAnExpiringMissedDay(User target) {
        missedDayOn(target, EXPIRING);
    }

    private void missedDayOn(LocalDate day) {
        missedDayOn(user, day);
    }

    private void missedDayOn(User target, LocalDate day) {
        EntityCheckDay row = new EntityCheckDay();
        row.setUser(target);
        row.setOwnerType(CheckDayOwnerType.USER);
        row.setOwnerId(target.getId());
        row.setDay(day);
        row.setOutcome(CheckDayOutcome.MISSED);
        checkDayRepository.save(row);
    }

    @Test
    @DisplayName("an earned nudge is sent, and recorded once")
    void sendsAndRecords() {
        giveAnExpiringMissedDay(user);

        assertThat(nudgeService.considerAccount(user, TODAY)).isTrue();

        verify(emailService).sendEngagementNudge(eq(user.getEmail()), any(), anyString(), any());
        assertThat(sendRepository.existsByUserIdAndKindAndSentOn(
                user.getId(), NudgeKind.XP_RECOVERY_WINDOW, TODAY)).isTrue();
    }

    /**
     * The switch is the whole reason phase 1 shipped before any sender. If it did not stop
     * a send, none of the rest of the consent machinery means anything.
     */
    @Test
    @DisplayName("an account that opted out is never mailed")
    void respectsTheOptOut() {
        giveAnExpiringMissedDay(user);
        preferencesService.setEngagementEmail(user, false);

        assertThat(nudgeService.considerAccount(user, TODAY)).isFalse();

        verify(emailService, never()).sendEngagementNudge(anyString(), any(), anyString(), any());
        assertThat(sendRepository.count()).isZero();
    }

    /**
     * An address nobody confirmed must not receive engagement mail — it may not belong to
     * the person who typed it, and mail to unconfirmed addresses is what turns a sending
     * domain's reputation bad. That cohort's repair path is the verification resend, which
     * is transactional.
     */
    @Test
    @DisplayName("an unverified address is never mailed")
    void skipsUnverifiedAddresses() {
        user.setEmailVerified(false);
        user = userRepository.save(user);
        giveAnExpiringMissedDay(user);

        assertThat(nudgeService.considerAccount(user, TODAY)).isFalse();
        verify(emailService, never()).sendEngagementNudge(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("the same nudge is not sent twice on the same day")
    void dedupesWithinTheDay() {
        giveAnExpiringMissedDay(user);

        assertThat(nudgeService.considerAccount(user, TODAY)).isTrue();
        assertThat(nudgeService.considerAccount(user, TODAY))
                .as("the hourly pass comes back round; the second visit must be silent")
                .isFalse();

        verify(emailService, times(1)).sendEngagementNudge(anyString(), any(), anyString(), any());
    }

    /**
     * Limit 2 from V25. Two triggers can each be individually justified on consecutive
     * days; the sum is a sender writing every morning.
     */
    @Test
    @DisplayName("a second nudge inside the minimum gap is suppressed")
    void honoursTheMinimumGapBetweenMails() {
        // A missed day for each of the three days under test. The window slides with the
        // reader's date, so the day that expires on TODAY+n is EXPIRING+n — without a row
        // there, the later passes would be silent for lack of a trigger rather than because
        // of the gap, and the test would pass while asserting nothing.
        missedDayOn(EXPIRING);
        missedDayOn(EXPIRING.plusDays(1));
        missedDayOn(EXPIRING.plusDays(3));

        assertThat(nudgeService.considerAccount(user, TODAY)).isTrue();

        assertThat(nudgeService.considerAccount(user, TODAY.plusDays(1))).isFalse();
        assertThat(nudgeService.considerAccount(user, TODAY.plusDays(3)))
                .as("three days later the gap has passed")
                .isTrue();
    }

    /**
     * Limit 3 from V25. The cap exists because the same provider allowance carries password
     * resets, and a reset that does not arrive is a far worse failure than a nudge that
     * does not.
     */
    @Test
    @DisplayName("the daily budget stops the pass")
    void honoursTheDailyCap() {
        for (int i = 0; i < 2; i++) {
            User other = verifiedUser();
            giveAnExpiringMissedDay(other);
            assertThat(nudgeService.considerAccount(other, TODAY)).isTrue();
        }

        assertThat(nudgeService.dailyBudgetRemaining(TODAY))
                .as("the cap for this test is 2, and 2 have gone out")
                .isFalse();
    }

    /**
     * The ordering the service documents: no row unless the mail was actually handed over.
     * A row written before a failed send would suppress the nudge for the rest of the day
     * with nothing to notice — the failure mode that looks like everything working.
     */
    @Test
    @DisplayName("a failed send records nothing, so it can be retried")
    void doesNotRecordAFailedSend() {
        giveAnExpiringMissedDay(user);
        doThrow(new RuntimeException("SMTP is down"))
                .when(emailService).sendEngagementNudge(anyString(), any(), anyString(), any());

        assertThat(nudgeService.considerAccount(user, TODAY)).isFalse();

        assertThat(sendRepository.existsByUserIdAndKindAndSentOn(
                user.getId(), NudgeKind.XP_RECOVERY_WINDOW, TODAY))
                .as("nothing was delivered, so nothing may claim it was")
                .isFalse();
    }

    @Test
    @DisplayName("an account with nothing going on is not mailed")
    void staysQuietWithoutATrigger() {
        assertThat(nudgeService.considerAccount(user, TODAY)).isFalse();
        verify(emailService, never()).sendEngagementNudge(anyString(), any(), anyString(), any());
    }

    /**
     * The nudge reads the account's own decay strategy, so the percentage it quotes is the
     * one that account will actually earn. A mail quoting the wrong number is worse than
     * one quoting none.
     */
    @Test
    @DisplayName("the decision carries the recovery percentage for this account's strategy")
    void quotesTheAccountsOwnDecay() {
        giveAnExpiringMissedDay(user);

        int percent = nudgeService.decideFor(user, TODAY).orElseThrow().remainingXpPercent();

        assertThat(percent)
                .as("GRADUAL bottoms out at 20% once a day is four or more late")
                .isEqualTo(20);
    }

    /**
     * Deleting the account must not be blocked by its send log, and must take it along.
     *
     * <p>The delete goes straight at the table, with the session cleared first. Through the
     * repository, Hibernate reorders the statements around the still-managed children and
     * complains about an association to a removed entity — which would be a test of
     * Hibernate's cascade rather than of the one V25 declares.
     */
    @Test
    @DisplayName("the send log cascades with the account")
    void cascadesOnAccountDeletion() {
        giveAnExpiringMissedDay(user);
        assertThat(nudgeService.considerAccount(user, TODAY)).isTrue();
        UUID deletedId = user.getId();

        entityManager.flush();
        entityManager.clear();
        entityManager.createNativeQuery("DELETE FROM users WHERE id = ?1")
                .setParameter(1, deletedId)
                .executeUpdate();

        assertThat(sendRepository.findAll().stream()
                .noneMatch(send -> deletedId.equals(send.getUser().getId())))
                .as("a send log has no meaning once the account is gone")
                .isTrue();
    }
}
