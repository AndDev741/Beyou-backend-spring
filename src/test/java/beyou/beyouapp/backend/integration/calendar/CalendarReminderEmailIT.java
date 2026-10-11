package beyou.beyouapp.backend.integration.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.calendar.CalendarReminderEmailService;
import beyou.beyouapp.backend.domain.calendar.CalendarService;
import beyou.beyouapp.backend.domain.calendar.CalendarTask;
import beyou.beyouapp.backend.domain.calendar.CalendarTaskRepository;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskRequestDTO;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.notification.preferences.dto.UpdateNotificationPreferencesDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

/**
 * The rules between a reminder going unanswered and a mail arriving: the grace hour, the 24-hour
 * staleness cut, the preference, the reactions that cancel it, the daily cap, and "once".
 *
 * <p>The owner lives in Sao Paulo (UTC-3), so a reminder computed in the server's zone lands three
 * hours away from the right one and falls on the wrong side of every boundary below. Tasks sit on
 * 2026-10-14; a 10:00 task there reminds at 13:00Z.
 */
@Transactional
@TestPropertySource(properties = "calendar.reminders.daily-cap=3")
class CalendarReminderEmailIT extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final LocalTime TEN = LocalTime.of(10, 0);
    /** 10:00 in Sao Paulo. */
    private static final Instant TEN_SP = Instant.parse("2026-10-14T13:00:00Z");

    @Autowired private CalendarReminderEmailService reminderService;
    @Autowired private CalendarService calendarService;
    @Autowired private CalendarTaskRepository taskRepository;
    @Autowired private NotificationPreferencesService preferencesService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    /** Mocked so nothing speaks SMTP, and so a send can be made to fail on demand. */
    @MockitoBean private EmailService emailService;

    private User owner;

    @BeforeEach
    void seed() {
        reset(emailService);
        owner = newUser("reminder-owner", true);
        setServiceClock(TEN_SP.minusSeconds(3600));
    }

    @Test
    @DisplayName("an unanswered reminder is mailed after the grace hour, in the owner's language, and only once")
    void mailedOnceAfterTheGrace() {
        UUID id = task("Sign the lease", DAY, TEN, 0);

        assertThat(reminderService.sendDue(TEN_SP.plusSeconds(90 * 60))).isEqualTo(1);
        verify(emailService).sendCalendarReminder(
                eq(owner.getEmail()), eq("Sign the lease"), any(), eq(DAY), eq(TEN), eq("pt"));
        assertThat(reload(id).getReminderEmailSentAt()).isEqualTo(TEN_SP.plusSeconds(90 * 60));

        reminderService.sendDue(TEN_SP.plusSeconds(95 * 60));
        verify(emailService, times(1)).sendCalendarReminder(eq(owner.getEmail()), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("no mail inside the grace hour; the mail goes the minute it ends")
    void waitsForTheGrace() {
        task("Sign the lease", DAY, TEN, 0);

        reminderService.sendDue(TEN_SP.plusSeconds(59 * 60));
        verifyNoMailForOwner();

        reminderService.sendDue(TEN_SP.plusSeconds(60 * 60));
        verify(emailService).sendCalendarReminder(eq(owner.getEmail()), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a reminder more than a day old is never mailed late")
    void neverMailedLate() {
        task("Sign the lease", DAY, TEN, 0);

        reminderService.sendDue(TEN_SP.plusSeconds(24 * 3600 + 60));

        verifyNoMailForOwner();
    }

    @Test
    @DisplayName("an untimed task's reminder runs off the owner's default time")
    void untimedUsesTheDefaultTime() {
        preferencesService.update(owner, new UpdateNotificationPreferencesDTO(null, null, null, LocalTime.of(7, 0)));
        task("Pay the bill", DAY, null, 0);

        // 07:00 in Sao Paulo is 10:00Z, so 11:05Z is past the grace; at the 09:00 default it
        // would not be due until 13:00Z.
        reminderService.sendDue(Instant.parse("2026-10-14T11:05:00Z"));

        verify(emailService).sendCalendarReminder(eq(owner.getEmail()), eq("Pay the bill"), any(), eq(DAY),
                isNull(), any());
    }

    @Test
    @DisplayName("reminder mail off means no mail; engagement mail off does not touch reminders")
    void preferences() {
        task("Sign the lease", DAY, TEN, 0);
        preferencesService.update(owner, new UpdateNotificationPreferencesDTO(true, false, null, null));

        reminderService.sendDue(TEN_SP.plusSeconds(90 * 60));
        verifyNoMailForOwner();

        preferencesService.update(owner, new UpdateNotificationPreferencesDTO(false, true, null, null));
        reminderService.sendDue(TEN_SP.plusSeconds(95 * 60));
        verify(emailService).sendCalendarReminder(eq(owner.getEmail()), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AE2: a clicked notification means no mail")
    void acknowledgedIsNotMailed() {
        UUID id = task("Sign the lease", DAY, TEN, 0);
        calendarService.acknowledge(owner, id);

        reminderService.sendDue(TEN_SP.plusSeconds(90 * 60));

        verifyNoMailForOwner();
    }

    @Test
    @DisplayName("AE3: a task completed before the hour is up is not mailed")
    void completedIsNotMailed() {
        UUID id = task("Sign the lease", DAY, TEN, 0);
        calendarService.toggleComplete(owner, id);

        reminderService.sendDue(TEN_SP.plusSeconds(90 * 60));

        verifyNoMailForOwner();
    }

    @Test
    @DisplayName("an address that was never verified gets nothing")
    void unverifiedAddressIsSkipped() {
        User unverified = newUser("reminder-unverified", false);
        calendarService.create(unverified, new CalendarTaskRequestDTO("Sign the lease", null, DAY, TEN, 0));

        reminderService.sendDue(TEN_SP.plusSeconds(90 * 60));

        verify(emailService, never()).sendCalendarReminder(eq(unverified.getEmail()), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a failed send is not marked, and the next pass tries again")
    void failedSendIsRetried() {
        UUID id = task("Sign the lease", DAY, TEN, 0);
        doThrow(new RuntimeException("smtp down")).doNothing()
                .when(emailService).sendCalendarReminder(anyString(), any(), any(), any(), any(), any());

        assertThat(reminderService.sendDue(TEN_SP.plusSeconds(90 * 60))).isZero();
        assertThat(reload(id).getReminderEmailSentAt()).as("a mail that never went out is not marked").isNull();

        assertThat(reminderService.sendDue(TEN_SP.plusSeconds(95 * 60))).isEqualTo(1);
        assertThat(reload(id).getReminderEmailSentAt()).isNotNull();
    }

    @Test
    @DisplayName("the daily cap stops the pass, oldest reminders first, counting mails already sent today")
    void dailyCap() {
        // One already went out today, so a cap of three leaves room for two.
        UUID earlier = task("already mailed", DAY, LocalTime.of(6, 0), 0);
        reminderService.sendDue(Instant.parse("2026-10-14T10:05:00Z"));
        reset(emailService);

        task("07:00", DAY, LocalTime.of(7, 0), 0);
        task("08:00", DAY, LocalTime.of(8, 0), 0);
        task("09:00", DAY, LocalTime.of(9, 0), 0);

        int sent = reminderService.sendDue(TEN_SP.plusSeconds(5 * 60));

        assertThat(sent).isEqualTo(2);
        verify(emailService).sendCalendarReminder(eq(owner.getEmail()), eq("07:00"), any(), any(), any(), any());
        verify(emailService).sendCalendarReminder(eq(owner.getEmail()), eq("08:00"), any(), any(), any(), any());
        verify(emailService, never()).sendCalendarReminder(eq(owner.getEmail()), eq("09:00"), any(), any(), any(), any());
        assertThat(reload(earlier).getReminderEmailSentAt()).isNotNull();
    }

    @Test
    @DisplayName("AE4: once mailed, moving the task to tomorrow mails tomorrow's reminder too")
    void movedTaskIsMailedAgain() {
        UUID id = task("Sign the lease", DAY, TEN, 0);
        reminderService.sendDue(TEN_SP.plusSeconds(90 * 60));
        // The edit arrives as its own request in production, reading the row the mark wrote. Left
        // in this session, the stale entity would write the old null back and re-arm by accident.
        assertThat(reload(id).getReminderEmailSentAt()).isNotNull();

        setServiceClock(TEN_SP.plusSeconds(2 * 3600));
        calendarService.update(owner, id,
                new CalendarTaskRequestDTO("Sign the lease", null, DAY.plusDays(1), LocalTime.of(15, 0), 0));
        // 15:00 tomorrow in Sao Paulo is 18:00Z.
        reminderService.sendDue(Instant.parse("2026-10-15T19:05:00Z"));

        verify(emailService, times(2)).sendCalendarReminder(eq(owner.getEmail()), any(), any(), any(), any(), any());
    }

    /**
     * The task is moved while its mail is on the way. The late "sent" mark must not land on the
     * moved task, or its new reminder would never be mailed.
     */
    @Test
    @DisplayName("a task moved during its send keeps its new reminder armed")
    void moveDuringTheSendIsNotStampedAsSent() {
        UUID id = task("Sign the lease", DAY, TEN, 0);
        setServiceClock(TEN_SP.plusSeconds(90 * 60 + 1));
        doAnswer(invocation -> {
            calendarService.update(owner, id,
                    new CalendarTaskRequestDTO("Sign the lease", null, DAY, LocalTime.of(17, 0), 0));
            return null;
        }).when(emailService).sendCalendarReminder(anyString(), any(), any(), any(), any(), any());

        reminderService.sendDue(TEN_SP.plusSeconds(90 * 60));

        assertThat(reload(id).getReminderEmailSentAt()).isNull();
    }

    // ---- helpers ----

    private UUID task(String title, LocalDate date, LocalTime time, int advance) {
        return calendarService.create(owner, new CalendarTaskRequestDTO(title, null, date, time, advance)).id();
    }

    private CalendarTask reload(UUID id) {
        entityManager.flush();
        entityManager.clear();
        return taskRepository.findById(id).orElseThrow();
    }

    private void verifyNoMailForOwner() {
        verify(emailService, never()).sendCalendarReminder(eq(owner.getEmail()), any(), any(), any(), any(), any());
    }

    /** The service's clock only stamps created/updated; the pass takes "now" as an argument. */
    private void setServiceClock(Instant instant) {
        Object target = AopTestUtils.getTargetObject(calendarService);
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(instant, ZoneOffset.UTC));
    }

    private User newUser(String tag, boolean verified) {
        User u = new User();
        u.setName(tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("America/Sao_Paulo");
        u.setLanguageInUse("pt");
        u.setEmailVerified(verified);
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
