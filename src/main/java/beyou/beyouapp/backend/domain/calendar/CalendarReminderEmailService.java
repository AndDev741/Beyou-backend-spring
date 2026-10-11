package beyou.beyouapp.backend.domain.calendar;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.notification.preferences.ReminderSettings;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Decides which calendar reminders are owed a mail, and sends them under a daily budget.
 *
 * <p>A reminder is mailed once, and only when nobody reacted to it in the browser: the task is
 * still open, the notification was never clicked, the grace period after its instant has passed,
 * and the account wants reminder mail. A reminder whose window closed while the job was not
 * running is skipped, never mailed late: "your 10:00 appointment" arriving the next afternoon is
 * worse than nothing.
 *
 * <p>Split from {@link CalendarReminderEmailScheduler} like the engagement pair: the scheduler
 * decides when a pass runs, this decides what it sends.
 *
 * <p><b>Send first, record second</b>, and no transaction around the pass. The reads and the
 * final mark each run in their own short transaction, so no database connection is held open
 * while a mail server takes its time. The mark happens only after the mail was handed over: a
 * failed send leaves the task unmarked and the next pass tries again, while the reverse (marking
 * first) would record a mail that never arrived.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CalendarReminderEmailService {

    /**
     * How far the candidate window reaches on each side of the UTC day. Two days covers the
     * widest gap between UTC and any timezone's date, plus the day the grace and max age span.
     */
    private static final int ZONE_SLACK_DAYS = 2;

    /** The largest advance in whole days: a task this far ahead can already be due. */
    private static final int MAX_ADVANCE_DAYS =
            (CalendarTask.MAX_ADVANCE_MINUTES + 24 * 60 - 1) / (24 * 60);

    private final CalendarTaskRepository taskRepository;
    private final NotificationPreferencesService preferencesService;
    private final EmailService emailService;

    /**
     * How long after its instant a reminder waits for a reaction before the mail goes. Matches
     * the hour the upcoming endpoint still hands a missed reminder to the browser.
     */
    @Value("${calendar.reminders.email-grace-minutes:60}")
    private int graceMinutes;

    /** How long after its instant a reminder may still be mailed. Past this it is stale. */
    @Value("${calendar.reminders.email-max-age-hours:24}")
    private int maxAgeHours;

    /**
     * Reminder mails per UTC day across every account. Its own budget, below the provider's
     * daily allowance, which also carries verification and reset mail and the engagement nudges.
     */
    @Value("${calendar.reminders.daily-cap:150}")
    private int dailyCap;

    /** One reminder that is owed a mail, with everything the send needs. */
    record DueReminder(
            UUID taskId,
            UUID userId,
            Instant seenUpdatedAt,
            Instant notifyAt,
            String email,
            String language,
            String title,
            String note,
            LocalDate date,
            LocalTime time) {
    }

    /**
     * One pass: every reminder owed a mail at {@code now}, oldest first, until the day's budget
     * runs out.
     *
     * @return how many mails went out
     */
    public int sendDue(Instant now) {
        List<DueReminder> due = findDue(now);
        if (due.isEmpty()) {
            return 0;
        }

        Instant utcMidnight = LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long budget = dailyCap - taskRepository.countByReminderEmailSentAtGreaterThanEqual(utcMidnight);

        int sent = 0;
        for (DueReminder reminder : due) {
            if (budget <= 0) {
                // Oldest first, so the reminders closest to going stale were the ones that got
                // through. The rest wait for the next UTC day's budget or age out.
                log.warn("Calendar reminder daily cap of {} reached; {} reminders not mailed this pass",
                        dailyCap, due.size() - sent);
                break;
            }
            if (send(reminder, now)) {
                sent++;
                budget--;
            }
        }
        return sent;
    }

    /**
     * The reminders owed a mail at {@code now}, oldest instant first. Read-only; nothing here
     * writes.
     */
    List<DueReminder> findDue(Instant now) {
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<CalendarTask> candidates = taskRepository.findReminderEmailCandidates(
                utcToday.minusDays(ZONE_SLACK_DAYS), utcToday.plusDays(ZONE_SLACK_DAYS + MAX_ADVANCE_DAYS));
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<UUID> owners = candidates.stream().map(task -> task.getUser().getId()).collect(Collectors.toSet());
        Map<UUID, ReminderSettings> settings = preferencesService.reminderSettings(owners);

        Duration grace = Duration.ofMinutes(graceMinutes);
        Duration maxAge = Duration.ofHours(maxAgeHours);
        return candidates.stream()
                .map(task -> dueOrNull(task, settings.get(task.getUser().getId()), now, grace, maxAge))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(DueReminder::notifyAt).thenComparing(DueReminder::taskId))
                .toList();
    }

    private static DueReminder dueOrNull(CalendarTask task, ReminderSettings settings, Instant now,
                                         Duration grace, Duration maxAge) {
        User owner = task.getUser();
        if (settings == null || !settings.email() || !isReachable(owner)) {
            return null;
        }
        Instant notifyAt = CalendarReminderTime.instantOf(task.getTaskDate(), task.getTaskTime(),
                task.getAdvanceMinutes(), UserDateResolver.zoneOf(owner), settings.defaultTime());
        if (now.isBefore(notifyAt.plus(grace)) || now.isAfter(notifyAt.plus(maxAge))) {
            return null;
        }
        return new DueReminder(task.getId(), owner.getId(), task.getUpdatedAt(), notifyAt,
                owner.getEmail(), owner.getLanguageInUse(), task.getTitle(), task.getNote(),
                task.getTaskDate(), task.getTaskTime());
    }

    /**
     * The same gate the engagement nudges use: an address that was never confirmed gets nothing
     * beyond the verification mail itself.
     */
    private static boolean isReachable(User owner) {
        return owner.getEmail() != null && !owner.getEmail().isBlank() && owner.isEmailVerified();
    }

    private boolean send(DueReminder reminder, Instant now) {
        try {
            emailService.sendCalendarReminder(reminder.email(), reminder.title(), reminder.note(),
                    reminder.date(), reminder.time(), reminder.language());
        } catch (Exception e) {
            // One dead mailbox must not end the pass for everyone after it. The task stays
            // unmarked, so the next pass tries again while the reminder is still fresh. The
            // ERROR line is the alert. Ids only: the title is the user's own words.
            log.error("Calendar reminder mail failed for task {} of user {}",
                    reminder.taskId(), reminder.userId(), e);
            return false;
        }

        if (taskRepository.markReminderEmailSent(reminder.taskId(), reminder.seenUpdatedAt(), now) == 0) {
            // Edited, completed or deleted while the mail was on its way. The mail went out
            // anyway; a moved task keeps its new reminder armed instead of being stamped as sent.
            log.info("Calendar task {} changed while its reminder mail was sent; left unmarked",
                    reminder.taskId());
        }
        return true;
    }
}
