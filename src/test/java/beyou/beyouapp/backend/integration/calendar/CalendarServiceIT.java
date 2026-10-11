package beyou.beyouapp.backend.integration.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.calendar.CalendarService;
import beyou.beyouapp.backend.domain.calendar.CalendarTask;
import beyou.beyouapp.backend.domain.calendar.CalendarTaskRepository;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarOverviewDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskCompletionDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskRequestDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.notification.preferences.dto.UpdateNotificationPreferencesDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

/**
 * Calendar task rules against a real Postgres: ownership, the XP toggle, re-arming on a move,
 * the upcoming window and the range check.
 *
 * <p>The owner lives in Sao Paulo, three hours behind UTC with no daylight saving, so an instant
 * computed in the server's zone instead of the owner's is three hours off and fails here wherever
 * the suite runs. The clock is fixed: "now" is 2026-10-14T13:00Z, 10:00 in Sao Paulo.
 */
@Transactional
class CalendarServiceIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-14T13:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Autowired private CalendarService calendarService;
    @Autowired private CalendarTaskRepository taskRepository;
    @Autowired private NotificationPreferencesService preferencesService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User owner;
    private User stranger;

    @BeforeEach
    void seed() {
        owner = newUser("calendar-owner", "America/Sao_Paulo");
        stranger = newUser("calendar-stranger", "America/Sao_Paulo");
        setClock(NOW);
    }

    // ---- create ----

    @Test
    @DisplayName("a created task carries its reminder instant in the owner's zone, and trims what it was given")
    void createComputesNotifyAtInTheOwnersZone() {
        CalendarTaskResponseDTO created = calendarService.create(owner,
                request("  Sign the lease  ", "   ", TODAY, LocalTime.of(10, 0), 60));

        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("Sign the lease");
        assertThat(created.note()).as("a blank note is no note").isNull();
        assertThat(created.advanceMinutes()).isEqualTo(60);
        assertThat(created.completed()).isFalse();
        assertThat(created.xpAwarded()).isZero();
        assertThat(created.notifyAt()).isEqualTo(Instant.parse("2026-10-14T12:00:00Z"));
    }

    @Test
    @DisplayName("an untimed task reminds at the owner's default time, and a missing advance is none")
    void untimedTaskUsesTheStoredDefaultTime() {
        preferencesService.update(owner, new UpdateNotificationPreferencesDTO(null, null, null, LocalTime.of(7, 30)));

        CalendarTaskResponseDTO created = calendarService.create(owner,
                request("Book the dentist", null, TODAY.plusDays(2), null, null));

        assertThat(created.time()).isNull();
        assertThat(created.advanceMinutes()).isZero();
        assertThat(created.notifyAt()).isEqualTo(Instant.parse("2026-10-16T10:30:00Z"));
    }

    // ---- edit re-arms (AE4) ----

    @Test
    @DisplayName("AE4: moving a task clears the click and the mail mark, so it reminds again")
    void movingTheTaskReArmsTheReminder() {
        UUID id = calendarService.create(owner, request("Call the bank", null, TODAY, LocalTime.of(10, 0), 0)).id();
        markReacted(id);

        calendarService.update(owner, id, request("Call the bank", null, TODAY.plusDays(1), LocalTime.of(15, 0), 0));

        CalendarTask stored = reload(id);
        assertThat(stored.getAcknowledgedAt()).isNull();
        assertThat(stored.getReminderEmailSentAt()).isNull();
    }

    @Test
    @DisplayName("changing only the advance also re-arms")
    void changingOnlyTheAdvanceReArms() {
        UUID id = calendarService.create(owner, request("Call the bank", null, TODAY, LocalTime.of(10, 0), 0)).id();
        markReacted(id);

        calendarService.update(owner, id, request("Call the bank", null, TODAY, LocalTime.of(10, 0), 30));

        assertThat(reload(id).getReminderEmailSentAt()).isNull();
    }

    @Test
    @DisplayName("editing only the title and note leaves an answered reminder answered")
    void editingTheWordsDoesNotReArm() {
        UUID id = calendarService.create(owner, request("Call the bank", null, TODAY, LocalTime.of(10, 0), 0)).id();
        markReacted(id);

        calendarService.update(owner, id, request("Call the bank about the card", "ask for a new one",
                TODAY, LocalTime.of(10, 0), 0));

        CalendarTask stored = reload(id);
        assertThat(stored.getTitle()).isEqualTo("Call the bank about the card");
        assertThat(stored.getAcknowledgedAt()).isNotNull();
        assertThat(stored.getReminderEmailSentAt()).isNotNull();
    }

    // ---- completion and XP (AE5) ----

    @Test
    @DisplayName("AE5: completing pays 10 XP and un-completing takes exactly that back")
    void completingPaysAndUncompletingTakesItBack() {
        double before = owner.getXpProgress().getXp();
        UUID id = calendarService.create(owner, request("Renew the passport", null, TODAY, null, 0)).id();

        CalendarTaskCompletionDTO done = calendarService.toggleComplete(owner, id);

        assertThat(done.task().completed()).isTrue();
        assertThat(done.task().completedAt()).isEqualTo(NOW);
        assertThat(done.task().xpAwarded()).isEqualTo(10.0);
        assertThat(done.refreshUi().refreshUser().xp()).isEqualTo(before + 10);
        assertThat(xpOf(owner)).isEqualTo(before + 10);

        CalendarTaskCompletionDTO undone = calendarService.toggleComplete(owner, id);

        assertThat(undone.task().completed()).isFalse();
        assertThat(undone.task().completedAt()).isNull();
        assertThat(undone.task().xpAwarded()).isZero();
        assertThat(undone.refreshUi().refreshUser().xp()).isEqualTo(before);
        assertThat(xpOf(owner)).isEqualTo(before);
    }

    /**
     * The stored amount is the truth, not the formula. A row that paid 7 (an older formula, say)
     * gives back 7, so a formula change between the two clicks leaves nothing behind.
     */
    @Test
    @DisplayName("un-completing takes back what the row says was paid, not what the formula says today")
    void uncompletingUsesTheStoredAmount() {
        UUID id = calendarService.create(owner, request("Renew the passport", null, TODAY, null, 0)).id();
        calendarService.toggleComplete(owner, id);
        double afterPaying = xpOf(owner);
        reload(id).setXpAwarded(7);

        calendarService.toggleComplete(owner, id);

        assertThat(xpOf(owner)).isEqualTo(afterPaying - 7);
    }

    // ---- ownership ----

    @Test
    @DisplayName("someone else's task answers not-found on every route and stays untouched")
    void someoneElsesTaskIsNotFound() {
        UUID id = calendarService.create(owner, request("Private errand", null, TODAY, LocalTime.of(10, 0), 0)).id();
        CalendarTaskRequestDTO hijack = request("Mine now", null, TODAY, LocalTime.of(11, 0), 0);

        assertNotFound(() -> calendarService.update(stranger, id, hijack));
        assertNotFound(() -> calendarService.toggleComplete(stranger, id));
        assertNotFound(() -> calendarService.acknowledge(stranger, id));
        assertNotFound(() -> calendarService.delete(stranger, id));

        CalendarTask stored = reload(id);
        assertThat(stored.getTitle()).isEqualTo("Private errand");
        assertThat(stored.getCompletedAt()).isNull();
        assertThat(stored.getAcknowledgedAt()).isNull();
    }

    @Test
    @DisplayName("an id that does not exist answers the same way as someone else's")
    void unknownIdIsNotFound() {
        assertNotFound(() -> calendarService.toggleComplete(owner, UUID.randomUUID()));
    }

    // ---- ack ----

    @Test
    @DisplayName("acknowledging twice keeps the first click")
    void acknowledgeIsIdempotent() {
        UUID id = calendarService.create(owner, request("Water the plants", null, TODAY, LocalTime.of(10, 0), 0)).id();

        calendarService.acknowledge(owner, id);
        setClock(NOW.plusSeconds(600));
        calendarService.acknowledge(owner, id);

        assertThat(reload(id).getAcknowledgedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("deleting removes the task for its owner")
    void deleteRemovesTheTask() {
        UUID id = calendarService.create(owner, request("Water the plants", null, TODAY, null, 0)).id();

        calendarService.delete(owner, id);
        entityManager.flush();

        assertThat(taskRepository.findById(id)).isEmpty();
    }

    // ---- upcoming ----

    /**
     * Now is 10:00 in Sao Paulo (13:00Z). The window is [12:00Z today, 13:00Z tomorrow], both
     * ends included.
     */
    @Test
    @DisplayName("upcoming lists open, unanswered reminders from an hour ago to a day ahead, soonest first")
    void upcomingWindow() {
        UUID missedRecently = create("missed 30 minutes ago", TODAY, LocalTime.of(9, 30), 0);
        create("missed 90 minutes ago", TODAY, LocalTime.of(8, 30), 0);
        UUID untimed = create("untimed, at the 09:00 default", TODAY, null, 0);
        UUID tomorrowMorning = create("tomorrow 09:00", TODAY.plusDays(1), LocalTime.of(9, 0), 0);
        create("tomorrow 11:00, past the window", TODAY.plusDays(1), LocalTime.of(11, 0), 0);
        UUID dayAhead = create("the day after, a day's notice", TODAY.plusDays(2), LocalTime.of(10, 0), 1440);
        UUID done = create("done already", TODAY, LocalTime.of(10, 30), 0);
        calendarService.toggleComplete(owner, done);
        UUID clicked = create("clicked already", TODAY, LocalTime.of(10, 30), 0);
        calendarService.acknowledge(owner, clicked);
        calendarService.create(stranger, request("someone else's", null, TODAY, LocalTime.of(10, 30), 0));

        List<CalendarTaskResponseDTO> upcoming = calendarService.upcoming(owner);

        assertThat(upcoming).extracting(CalendarTaskResponseDTO::id)
                .containsExactly(untimed, missedRecently, tomorrowMorning, dayAhead);
        assertThat(upcoming).extracting(CalendarTaskResponseDTO::notifyAt).containsExactly(
                Instant.parse("2026-10-14T12:00:00Z"),
                Instant.parse("2026-10-14T12:30:00Z"),
                Instant.parse("2026-10-15T12:00:00Z"),
                Instant.parse("2026-10-15T13:00:00Z"));
    }

    // ---- range ----

    @Test
    @DisplayName("the overview refuses a missing bound, a backwards range and one over 62 days")
    void rangeValidation() {
        assertRangeInvalid(null, TODAY);
        assertRangeInvalid(TODAY, null);
        assertRangeInvalid(TODAY, TODAY.minusDays(1));
        assertRangeInvalid(TODAY, TODAY.plusDays(62));

        CalendarOverviewDTO longest = calendarService.getOverview(owner, TODAY, TODAY.plusDays(61));
        assertThat(longest.days()).hasSize(62);
        CalendarOverviewDTO single = calendarService.getOverview(owner, TODAY, TODAY);
        assertThat(single.days()).hasSize(1);
    }

    // ---- helpers ----

    private UUID create(String title, LocalDate date, LocalTime time, int advance) {
        return calendarService.create(owner, request(title, null, date, time, advance)).id();
    }

    private static CalendarTaskRequestDTO request(String title, String note, LocalDate date, LocalTime time,
                                                  Integer advance) {
        return new CalendarTaskRequestDTO(title, note, date, time, advance);
    }

    private void markReacted(UUID id) {
        CalendarTask task = reload(id);
        task.setAcknowledgedAt(NOW);
        task.setReminderEmailSentAt(NOW);
        entityManager.flush();
    }

    private CalendarTask reload(UUID id) {
        entityManager.flush();
        return taskRepository.findById(id).orElseThrow();
    }

    private double xpOf(User user) {
        entityManager.flush();
        return userRepository.findById(user.getId()).orElseThrow().getXpProgress().getXp();
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.CALENDAR_TASK_NOT_FOUND);
    }

    private void assertRangeInvalid(LocalDate from, LocalDate to) {
        assertThatThrownBy(() -> calendarService.getOverview(owner, from, to))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.CALENDAR_RANGE_INVALID);
    }

    private void setClock(Instant instant) {
        Object target = AopTestUtils.getTargetObject(calendarService);
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(instant, ZoneOffset.UTC));
    }

    private User newUser(String tag, String timezone) {
        User u = new User();
        u.setName(tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone(timezone);
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
