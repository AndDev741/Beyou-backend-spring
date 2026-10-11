package beyou.beyouapp.backend.unit.calendar;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.calendar.CalendarReminderEmailScheduler;
import beyou.beyouapp.backend.domain.calendar.CalendarReminderEmailService;
import beyou.beyouapp.backend.monitoring.ReminderJobHeartbeat;

/** When the reminder pass runs, and that it says so to the collector only when it finished. */
class CalendarReminderEmailSchedulerUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-14T14:35:00Z");

    private CalendarReminderEmailService reminderService;
    private ReminderJobHeartbeat heartbeat;
    private CalendarReminderEmailScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        reminderService = mock(CalendarReminderEmailService.class);
        heartbeat = mock(ReminderJobHeartbeat.class);
        scheduler = new CalendarReminderEmailScheduler(reminderService, heartbeat);
        invoke("setClock", Clock.class, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void invoke(String name, Class<?> type, Object value) throws Exception {
        Method method = CalendarReminderEmailScheduler.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(scheduler, value);
    }

    @Test
    @DisplayName("switched off, the pass does nothing and checks in nowhere")
    void disabledDoesNothing() throws Exception {
        invoke("setEnabled", boolean.class, false);

        scheduler.sendDueReminders();

        verifyNoInteractions(reminderService, heartbeat);
    }

    @Test
    @DisplayName("switched on, the pass runs at the clock's now and then checks in")
    void enabledRunsAndChecksIn() throws Exception {
        invoke("setEnabled", boolean.class, true);
        when(reminderService.sendDue(NOW)).thenReturn(2);

        scheduler.sendDueReminders();

        verify(reminderService).sendDue(NOW);
        verify(heartbeat).signalCycleCompleted();
    }

    /** A pass that died must leave the collector waiting: the missing check-in is the alert. */
    @Test
    @DisplayName("a pass that throws does not check in")
    void failedPassDoesNotCheckIn() throws Exception {
        invoke("setEnabled", boolean.class, true);
        when(reminderService.sendDue(any())).thenThrow(new IllegalStateException("database gone"));

        assertThatThrownBy(scheduler::sendDueReminders).isInstanceOf(IllegalStateException.class);

        verify(heartbeat, never()).signalCycleCompleted();
    }
}
