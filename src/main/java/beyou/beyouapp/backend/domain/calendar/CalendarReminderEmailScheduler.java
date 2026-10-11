package beyou.beyouapp.backend.domain.calendar;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.monitoring.ReminderJobHeartbeat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Mails the calendar reminders nobody reacted to, every five minutes.
 *
 * <p>Five minutes because the mail is a fallback with an hour's grace, so a reminder that comes
 * due between passes is mailed at most five minutes later than planned. Unlike the engagement
 * pass there is no hour to wait for: a reminder is due at its own instant, in its owner's zone,
 * and {@link CalendarReminderEmailService} works that out per task.
 *
 * <p><b>On by default</b>, unlike {@code engagement.enabled}. These are reminders the user set
 * themselves, about their own tasks, so they need no launch gate; the flag is an incident switch.
 * It is off in the test and e2e profiles so no suite ever talks to a mail server.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CalendarReminderEmailScheduler {

    private final CalendarReminderEmailService reminderService;
    private final ReminderJobHeartbeat heartbeat;

    @Value("${calendar.reminders.email-enabled:true}")
    private boolean enabled;

    /** No {@code Clock} bean exists; tests replace this one, as with the other schedulers. */
    private Clock clock = Clock.systemDefaultZone();

    @Scheduled(cron = "0 */5 * * * *")
    public void sendDueReminders() {
        if (!enabled) {
            return;
        }

        int sent = reminderService.sendDue(clock.instant().truncatedTo(ChronoUnit.MICROS));
        if (sent > 0) {
            log.info("Calendar reminder pass finished: {} mails sent", sent);
        } else {
            log.debug("Calendar reminder pass finished: nothing due");
        }

        // After the work, so a pass that threw (the database gone, say) leaves the collector
        // waiting, which is the alert. Fail-open: the heartbeat swallows its own errors.
        heartbeat.signalCycleCompleted();
    }

    /** Test seam, mirroring {@code EngagementNudgeScheduler}. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Test seam: the flag is read from configuration in every real deployment. */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
