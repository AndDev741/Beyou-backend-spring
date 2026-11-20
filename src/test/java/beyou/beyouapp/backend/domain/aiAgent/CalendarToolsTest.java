package beyou.beyouapp.backend.domain.aiAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.domain.calendar.CalendarService;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarDayDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarGoalDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarMoodDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarOverviewDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineSource;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskCompletionDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskRequestDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.notification.preferences.ReminderSettings;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import jakarta.validation.Validation;

/**
 * The assistant's calendar tools, called the way the framework calls them: a raw JSON string
 * through the MethodToolCallback, so a binding miss shows up here and not as a wasted model turn.
 *
 * <p>The owner lives in Sao Paulo, three hours behind UTC. A reminder handed to the model as the
 * stored UTC instant would read three hours late, which is the mistake these tools exist to avoid.
 */
@ExtendWith(MockitoExtension.class)
class CalendarToolsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 21);

    @Mock private CalendarService calendarService;
    @Mock private UserService userService;
    @Mock private NotificationPreferencesService notificationPreferencesService;

    @InjectMocks private Tools tools;

    private final UUID userId = UUID.randomUUID();
    private final ToolContext toolContext = new ToolContext(Map.of("userId", userId));
    private final User user = new User();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(tools, "validator", Validation.buildDefaultValidatorFactory().getValidator());
        user.setId(userId);
        user.setTimezone("America/Sao_Paulo");
        // Lenient: the refresh-domain test calls no tool.
        lenient().when(userService.findUserById(userId)).thenReturn(user);
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no tool named " + name));
    }

    private void remindersOn() {
        when(notificationPreferencesService.reminderSettings(userId)).thenReturn(ReminderSettings.DEFAULTS);
    }

    private static CalendarTaskResponseDTO task(UUID id, String title, String note, LocalDate date, LocalTime time,
                                                int advance, boolean completed, Instant notifyAt) {
        return new CalendarTaskResponseDTO(id, title, note, date, time, advance, completed,
                completed ? Instant.parse("2026-11-20T15:00:00Z") : null, completed ? 10 : 0, notifyAt);
    }

    private static CalendarTaskResponseDTO open(String title, LocalDate date, LocalTime time) {
        return task(UUID.randomUUID(), title, null, date, time, 0, false, Instant.parse("2026-11-21T12:00:00Z"));
    }

    // ---- create ----

    @Test
    void createBindsTheFieldsAndGivesTheReminderInTheUsersOwnClock() {
        remindersOn();
        UUID id = UUID.randomUUID();
        when(calendarService.tasksBetween(user, DAY, DAY)).thenReturn(List.of());
        // 09:30 with 30 minutes of notice is 09:00 in Sao Paulo, 12:00 in UTC.
        when(calendarService.create(eq(user), any())).thenReturn(task(id, "Pay the electricity bill", null, DAY,
                LocalTime.of(9, 30), 30, false, Instant.parse("2026-11-21T12:00:00Z")));

        String result = callback("createCalendarTask").call("""
                {"title": "Pay the electricity bill", "date": "2026-11-21", "time": "9:30", "advanceMinutes": 30}
                """, toolContext);

        verify(calendarService).create(user, new CalendarTaskRequestDTO(
                "Pay the electricity bill", null, DAY, LocalTime.of(9, 30), 30));
        assertTrue(result.contains(id.toString()), result);
        assertTrue(result.contains("2026-11-21T09:00"), "the reminder in the user's clock: " + result);
        assertFalse(result.contains("12:00"), "never the UTC instant: " + result);
        assertTrue(result.contains("\"browser\":true") && result.contains("\"defaultTime\":\"09:00\""), result);
    }

    @Test
    void createWithoutADayPutsTheTaskOnTheUsersToday() {
        remindersOn();
        LocalDate before = UserDateResolver.today(user);
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of());
        when(calendarService.create(eq(user), any())).thenReturn(open("Call Ana", before, null));

        callback("createCalendarTask").call("""
                {"title": "Call Ana"}
                """, toolContext);

        ArgumentCaptor<CalendarTaskRequestDTO> sent = ArgumentCaptor.forClass(CalendarTaskRequestDTO.class);
        verify(calendarService).create(eq(user), sent.capture());
        LocalDate after = UserDateResolver.today(user);
        assertTrue(sent.getValue().date().equals(before) || sent.getValue().date().equals(after),
                "today in the user's zone, not the server's: " + sent.getValue().date());
        assertEquals(null, sent.getValue().time(), "no time asked for, none set");
    }

    @Test
    void askingTwiceForTheSameTaskReturnsTheOneAlreadyThere() {
        remindersOn();
        CalendarTaskResponseDTO existing = open("Pay the rent", DAY, LocalTime.of(9, 30));
        when(calendarService.tasksBetween(user, DAY, DAY)).thenReturn(List.of(existing));

        String result = callback("createCalendarTask").call("""
                {"title": "pay the rent ", "date": "2026-11-21", "time": "09:30"}
                """, toolContext);

        verify(calendarService, never()).create(any(), any());
        assertTrue(result.contains(existing.id().toString()), result);
        assertTrue(result.contains("already"), result);
    }

    @Test
    void aDoneTaskOrAnotherTimeIsNotADuplicate() {
        remindersOn();
        CalendarTaskResponseDTO doneOne = task(UUID.randomUUID(), "Pay the rent", null, DAY, LocalTime.of(9, 30), 0,
                true, Instant.parse("2026-11-21T12:30:00Z"));
        CalendarTaskResponseDTO otherTime = open("Pay the rent", DAY, LocalTime.of(18, 0));
        when(calendarService.tasksBetween(user, DAY, DAY)).thenReturn(List.of(doneOne, otherTime));
        when(calendarService.create(eq(user), any())).thenReturn(open("Pay the rent", DAY, LocalTime.of(9, 30)));

        callback("createCalendarTask").call("""
                {"title": "Pay the rent", "date": "2026-11-21", "time": "09:30"}
                """, toolContext);

        verify(calendarService).create(eq(user), any());
    }

    @Test
    void aWeekdayForADateComesBackAsSomethingTheModelCanFix() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> callback("createCalendarTask").call("""
                {"title": "Pay the rent", "date": "Friday"}
                """, toolContext));

        assertTrue(rootMessage(e).contains("YYYY-MM-DD"), rootMessage(e));
        verify(calendarService, never()).create(any(), any());
    }

    @Test
    void aTitleTooShortIsRefusedBeforeTheService() {
        assertThrows(RuntimeException.class, () -> callback("createCalendarTask").call("""
                {"title": "x", "date": "2026-11-21"}
                """, toolContext));
        verify(calendarService, never()).create(any(), any());
    }

    // ---- edit ----

    @Test
    void editKeepsEveryFieldTheModelLeftOut() {
        CalendarTaskResponseDTO current = task(UUID.randomUUID(), "Dentist", "bring the card", DAY,
                LocalTime.of(15, 0), 60, false, Instant.parse("2026-11-21T17:00:00Z"));
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of(current));
        when(calendarService.update(eq(user), eq(current.id()), any())).thenReturn(current);

        callback("editCalendarTask").call("""
                {"task": "dentist", "title": "Dentist appointment"}
                """, toolContext);

        verify(calendarService).update(user, current.id(), new CalendarTaskRequestDTO(
                "Dentist appointment", "bring the card", DAY, LocalTime.of(15, 0), 60));
    }

    @Test
    void editTakesTheTimeAndTheNoteOffOnlyWhenAskedByName() {
        CalendarTaskResponseDTO current = task(UUID.randomUUID(), "Dentist", "bring the card", DAY,
                LocalTime.of(15, 0), 60, false, Instant.parse("2026-11-21T17:00:00Z"));
        when(calendarService.tasksBetween(user, DAY, DAY)).thenReturn(List.of(current));
        when(calendarService.update(eq(user), eq(current.id()), any())).thenReturn(current);

        callback("editCalendarTask").call("""
                {"task": "Dentist", "onDate": "2026-11-21", "clearTime": true, "clearNote": true, "date": "2026-11-23"}
                """, toolContext);

        verify(calendarService).update(user, current.id(), new CalendarTaskRequestDTO(
                "Dentist", null, LocalDate.of(2026, 11, 23), null, 60));
    }

    // ---- complete ----

    @Test
    void markingADoneTaskDoneChangesNothing() {
        CalendarTaskResponseDTO done = task(UUID.randomUUID(), "Pay the rent", null, DAY, null, 0, true,
                Instant.parse("2026-11-21T12:00:00Z"));
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of(done));

        String result = callback("completeCalendarTask").call("""
                {"task": "Pay the rent"}
                """, toolContext);

        verify(calendarService, never()).toggleComplete(any(), any());
        assertTrue(result.contains("\"xpChange\":0"), result);
    }

    @Test
    void markingAnOpenTaskDonePaysAndReportsIt() {
        CalendarTaskResponseDTO current = open("Pay the rent", DAY, null);
        CalendarTaskResponseDTO after = task(current.id(), "Pay the rent", null, DAY, null, 0, true, current.notifyAt());
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of(current));
        when(calendarService.toggleComplete(user, current.id())).thenReturn(new CalendarTaskCompletionDTO(after, null));

        String result = callback("completeCalendarTask").call("""
                {"task": "Pay the rent", "done": true}
                """, toolContext);

        verify(calendarService).toggleComplete(user, current.id());
        assertTrue(result.contains("\"xpChange\":10.0"), result);
        assertFalse(result.contains("reminderAt"), "a done task has no reminder left: " + result);
    }

    @Test
    void openingADoneTaskTakesItsXpBack() {
        CalendarTaskResponseDTO done = task(UUID.randomUUID(), "Pay the rent", null, DAY, null, 0, true,
                Instant.parse("2026-11-21T12:00:00Z"));
        CalendarTaskResponseDTO reopened = open("Pay the rent", DAY, null);
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of(done));
        when(calendarService.toggleComplete(user, done.id())).thenReturn(new CalendarTaskCompletionDTO(reopened, null));

        String result = callback("completeCalendarTask").call("""
                {"task": "Pay the rent", "done": false}
                """, toolContext);

        assertTrue(result.contains("\"xpChange\":-10.0"), result);
    }

    // ---- naming a task ----

    @Test
    void twoTasksWithTheTitleAreNeverAGuess() {
        CalendarTaskResponseDTO monday = open("Gym", DAY, LocalTime.of(7, 0));
        CalendarTaskResponseDTO tuesday = open("Gym", DAY.plusDays(1), LocalTime.of(7, 0));
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of(monday, tuesday));

        RuntimeException e = assertThrows(RuntimeException.class, () -> callback("deleteCalendarTask").call("""
                {"task": "gym"}
                """, toolContext));

        String message = rootMessage(e);
        assertTrue(message.contains(monday.id().toString()) && message.contains(tuesday.id().toString()), message);
        assertTrue(message.contains("onDate"), message);
        verify(calendarService, never()).delete(any(), any());
    }

    @Test
    void anUnknownTitleListsWhatIsThere() {
        CalendarTaskResponseDTO rent = open("Pay the rent", DAY, null);
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of(rent));

        RuntimeException e = assertThrows(RuntimeException.class, () -> callback("deleteCalendarTask").call("""
                {"task": "dentist"}
                """, toolContext));

        BusinessException business = assertInstanceOf(BusinessException.class, rootCause(e));
        assertEquals(ErrorKey.CALENDAR_TASK_NOT_FOUND, business.getErrorKey());
        assertTrue(business.getMessage().contains("Pay the rent") && business.getMessage().contains(rent.id().toString()),
                business.getMessage());
        verify(calendarService, never()).delete(any(), any());
    }

    @Test
    void anIdOutsideTheTitleWindowIsStillFound() {
        CalendarTaskResponseDTO farAway = open("Renew the passport", DAY.plusYears(1), null);
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of());
        when(calendarService.get(user, farAway.id())).thenReturn(farAway);

        callback("deleteCalendarTask").call("{\"task\": \"" + farAway.id() + "\"}", toolContext);

        verify(calendarService).delete(user, farAway.id());
    }

    @Test
    void aTitleMatchesAsAUniquePartOfIt() {
        CalendarTaskResponseDTO rent = open("Pay the rent", DAY, null);
        CalendarTaskResponseDTO dentist = open("Dentist", DAY, null);
        when(calendarService.tasksBetween(eq(user), any(), any())).thenReturn(List.of(rent, dentist));

        callback("deleteCalendarTask").call("""
                {"task": "rent"}
                """, toolContext);

        verify(calendarService).delete(user, rent.id());
    }

    // ---- read ----

    @Test
    void getCalendarReadsAWeekFromTodayAndLeavesEmptyDaysAndTheMoodOut() {
        remindersOn();
        LocalDate today = UserDateResolver.today(user);
        CalendarTaskResponseDTO rent = task(UUID.randomUUID(), "Pay the rent", null, today, LocalTime.of(9, 30), 0,
                false, Instant.parse("2026-11-21T12:30:00Z"));
        CalendarDayDTO busy = new CalendarDayDTO(today, List.of(rent),
                List.of(new CalendarRoutineDTO(UUID.randomUUID(), "Morning", "lucide:sun", CalendarRoutineSource.SNAPSHOT, 6, 4, false)),
                List.of(new CalendarGoalDTO(UUID.randomUUID(), "Run 10k", "lucide:trophy", false)),
                new CalendarMoodDTO(2, true));
        CalendarDayDTO empty = new CalendarDayDTO(today.plusDays(1), List.of(), List.of(), List.of(),
                new CalendarMoodDTO(5, false));
        when(calendarService.getOverview(user, today, today.plusDays(6)))
                .thenReturn(new CalendarOverviewDTO(today, today.plusDays(6), today, List.of(busy, empty)));

        String result = callback("getCalendar").call("{}", toolContext);

        assertTrue(result.contains("Pay the rent") && result.contains("Morning: 4 of 6 done")
                && result.contains("Run 10k"), result);
        assertFalse(result.contains(today.plusDays(1).toString() + "\",\"weekday"),
                "a day with nothing on it is left out: " + result);
        assertFalse(result.contains("mood") || result.contains("level"), "the mood stays with its own tools: " + result);
        assertTrue(result.contains("\"reminders\""), result);
    }

    // ---- what the client refreshes ----

    @Test
    void theWritesAskTheClientToReReadTheCalendarAndTheReadDoesNot() {
        assertEquals(List.of("calendar"), AgentToolDomains.domainsOf("createCalendarTask"));
        assertEquals(List.of("calendar"), AgentToolDomains.domainsOf("editCalendarTask"));
        assertEquals(List.of("calendar"), AgentToolDomains.domainsOf("deleteCalendarTask"));
        assertEquals(List.of("calendar", "perfil"), AgentToolDomains.domainsOf("completeCalendarTask"),
                "completing pays XP, so the profile re-reads too");
        assertEquals(List.of(), AgentToolDomains.domainsOf("getCalendar"));
    }

    private static Throwable rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String rootMessage(Throwable e) {
        return String.valueOf(rootCause(e).getMessage());
    }
}
