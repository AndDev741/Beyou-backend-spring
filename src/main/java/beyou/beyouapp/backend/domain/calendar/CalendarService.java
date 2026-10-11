package beyou.beyouapp.backend.domain.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.calendar.dto.CalendarOverviewDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskCompletionDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskRequestDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;
import beyou.beyouapp.backend.domain.common.CheckXpCalculator;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.preferences.NotificationPreferencesService;
import beyou.beyouapp.backend.notification.preferences.ReminderSettings;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;

/**
 * Calendar tasks and the calendar's day-by-day overview.
 *
 * <p>The rules that live here and nowhere else, because two clients would drift on each:
 * <ol>
 *   <li><b>A task is only ever reachable by its owner.</b> Every lookup goes through
 *       {@link CalendarTaskRepository#findByIdAndUserId}, so someone else's task and a missing
 *       one get the same 404.</li>
 *   <li><b>Completing pays like a task check at the lowest rung, and un-completing takes back
 *       exactly what was paid.</b> The amount is stored on the row, so a formula change between
 *       the two never leaves XP behind.</li>
 *   <li><b>Moving a task re-arms its reminder.</b> A new date, time or advance clears the
 *       acknowledgement and the mail mark, so the moved reminder fires on both channels.</li>
 * </ol>
 *
 * <p>Deliberately not cached, like mood and the notebook. Every read is a date range, so a
 * cache keyed on the user would answer one range with another's days, and a composite key would
 * need evicting on every task write, every routine check and every mood tap that feeds the
 * overview. The reads are a handful of indexed queries over at most nine weeks.
 */
@Service
@RequiredArgsConstructor
public class CalendarService {

    /**
     * How many days one overview may span, counted inclusively. A month grid with its padding
     * weeks is at most 42; this leaves room for a client that asks for two months at once.
     */
    static final int MAX_RANGE_DAYS = 62;

    /**
     * How late an upcoming reminder may still be handed to the browser. Matches the mail's grace
     * period: a reminder missed by less than this is shown at once when a tab opens, and one
     * missed by more is the mail's to deliver.
     */
    static final Duration UPCOMING_LOOKBACK = Duration.ofMinutes(60);

    /** How far ahead the browser schedules timers. It polls again long before this runs out. */
    static final Duration UPCOMING_LOOKAHEAD = Duration.ofHours(24);

    private final CalendarTaskRepository taskRepository;
    private final CalendarTaskMapper taskMapper;
    private final CalendarOverviewBuilder overviewBuilder;
    private final NotificationPreferencesService preferencesService;
    private final XpCalculatorService xpCalculatorService;
    private final RefreshUiDtoBuilder refreshUiDtoBuilder;
    private final UserCacheEvictService userCacheEvictService;
    private final UserRepository userRepository;

    /**
     * Not injected, for the same reason as {@code MoodService}'s: there is no {@code Clock} bean.
     * Non-final so Lombok leaves it out of the constructor; tests replace it to fix "now".
     */
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Everything the calendar draws for {@code [from, to]}, one entry per day.
     *
     * @throws BusinessException {@link ErrorKey#CALENDAR_RANGE_INVALID} when either bound is
     *                           missing, {@code to} is before {@code from}, or the range is
     *                           longer than {@link #MAX_RANGE_DAYS}
     */
    @Transactional(readOnly = true)
    public CalendarOverviewDTO getOverview(User user, LocalDate from, LocalDate to) {
        validateRange(from, to);
        ReminderSettings settings = preferencesService.reminderSettings(user.getId());
        return overviewBuilder.build(
                user.getId(), from, to,
                UserDateResolver.today(user, clock),
                UserDateResolver.zoneOf(user),
                settings.defaultTime());
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new BusinessException(ErrorKey.CALENDAR_RANGE_INVALID, "Both 'from' and 'to' are required");
        }
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorKey.CALENDAR_RANGE_INVALID, "'to' is before 'from'");
        }
        // Inclusive, so a single day is 1 and the limit reads the way it is written.
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorKey.CALENDAR_RANGE_INVALID,
                    "Range of " + days + " days exceeds the maximum of " + MAX_RANGE_DAYS);
        }
    }

    @Transactional
    public CalendarTaskResponseDTO create(User user, CalendarTaskRequestDTO request) {
        Instant now = now();
        CalendarTask task = new CalendarTask();
        // A reference, not the principal: the principal was loaded by the security filter in
        // another session, and only its id is needed for the foreign key.
        task.setUser(userRepository.getReferenceById(user.getId()));
        apply(task, request);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return toResponse(taskRepository.save(task), user);
    }

    /**
     * Replaces the task's fields with the request's. When the date, time or advance changed, the
     * reminder is re-armed: the browser shows it again and, if nobody reacts, the mail goes out
     * again. Editing only the title or the note leaves the reminder as it was.
     *
     * <p>The re-arm also happens on a completed task, where it changes nothing until the task is
     * un-completed, and then makes the moved reminder fire as it should.
     */
    @Transactional
    public CalendarTaskResponseDTO update(User user, UUID taskId, CalendarTaskRequestDTO request) {
        CalendarTask task = owned(user, taskId);

        boolean rescheduled = !Objects.equals(task.getTaskDate(), request.date())
                || !Objects.equals(task.getTaskTime(), minutesOf(request.time()))
                || task.getAdvanceMinutes() != advanceOf(request);

        apply(task, request);
        if (rescheduled) {
            task.setAcknowledgedAt(null);
            task.setReminderEmailSentAt(null);
        }
        task.setUpdatedAt(now());
        return toResponse(task, user);
    }

    /**
     * Deletes the task. XP a completed task paid stays paid: the task was done, and deleting it
     * afterwards is tidying up, not undoing.
     */
    @Transactional
    public void delete(User user, UUID taskId) {
        taskRepository.delete(owned(user, taskId));
    }

    /**
     * Completes an open task or re-opens a completed one, moving XP either way.
     *
     * <p>The amount is the task-check formula at the lowest priority rung with no streak, the
     * same thing an unrated one-time routine task pays. It goes to the person only: a calendar
     * task has no category, habit or routine to share it with.
     */
    @Transactional
    public CalendarTaskCompletionDTO toggleComplete(User user, UUID taskId) {
        CalendarTask task = owned(user, taskId);
        // The managed owner, loaded through the task in this transaction: XP is written through
        // it, and the principal from the security context belongs to another session.
        User owner = task.getUser();
        Instant now = now();

        if (task.isCompleted()) {
            double paid = task.getXpAwarded();
            if (paid > 0) {
                xpCalculatorService.removeXpFromUserOnly(owner, paid);
            }
            task.setCompletedAt(null);
            task.setXpAwarded(0);
        } else {
            double xp = CheckXpCalculator.calculate(Task.PRIORITY_WHEN_UNSET, Task.PRIORITY_WHEN_UNSET, 0);
            xpCalculatorService.addXpToUserOnly(owner, xp);
            task.setCompletedAt(now);
            task.setXpAwarded(xp);
        }
        task.setUpdatedAt(now);

        userCacheEvictService.evictAllUserCaches(owner.getId());
        RefreshUiDTO refreshUi = refreshUiDtoBuilder.buildRefreshUiDto(
                UserDateResolver.today(owner, clock), null, List.of(), null, owner);
        return new CalendarTaskCompletionDTO(toResponse(task, owner), refreshUi);
    }

    /**
     * Records that the reminder got a reaction: the browser notification was clicked. Idempotent,
     * and the first click is the one kept. A reacted reminder is never mailed.
     */
    @Transactional
    public void acknowledge(User user, UUID taskId) {
        CalendarTask task = owned(user, taskId);
        if (task.getAcknowledgedAt() == null) {
            task.setAcknowledgedAt(now());
        }
    }

    /**
     * The reminders the browser should show: open, unacknowledged, with an instant from an hour
     * ago to a day ahead, soonest first.
     *
     * <p>The browser preference is not applied here. Whether to notify also depends on a
     * permission only the browser knows, so the client decides; this stays a plain list.
     */
    @Transactional(readOnly = true)
    public List<CalendarTaskResponseDTO> upcoming(User user) {
        Instant now = now();
        Instant earliest = now.minus(UPCOMING_LOOKBACK);
        Instant latest = now.plus(UPCOMING_LOOKAHEAD);
        ZoneId zone = UserDateResolver.zoneOf(user);
        LocalTime defaultTime = preferencesService.reminderSettings(user.getId()).defaultTime();

        // The instant is computed in Java, so the query narrows by day. A task's own day is never
        // before its reminder's, and at most the largest advance (plus a day for DST slack) after.
        LocalDate fromDay = LocalDate.ofInstant(earliest, zone);
        LocalDate toDay = LocalDate.ofInstant(
                latest.plus(Duration.ofMinutes(CalendarTask.MAX_ADVANCE_MINUTES)).plus(Duration.ofDays(1)), zone);

        return taskRepository
                .findByUserIdAndCompletedAtIsNullAndAcknowledgedAtIsNullAndTaskDateBetween(
                        user.getId(), fromDay, toDay)
                .stream()
                .map(task -> taskMapper.toResponseDTO(task, zone, defaultTime))
                .filter(dto -> !dto.notifyAt().isBefore(earliest) && !dto.notifyAt().isAfter(latest))
                .sorted(Comparator.comparing(CalendarTaskResponseDTO::notifyAt)
                        .thenComparing(CalendarTaskResponseDTO::id))
                .toList();
    }

    private CalendarTask owned(User user, UUID taskId) {
        return taskRepository.findByIdAndUserId(taskId, user.getId())
                .orElseThrow(() -> new BusinessException(ErrorKey.CALENDAR_TASK_NOT_FOUND,
                        "Calendar task not found"));
    }

    private CalendarTaskResponseDTO toResponse(CalendarTask task, User user) {
        LocalTime defaultTime = preferencesService.reminderSettings(user.getId()).defaultTime();
        return taskMapper.toResponseDTO(task, UserDateResolver.zoneOf(user), defaultTime);
    }

    private static void apply(CalendarTask task, CalendarTaskRequestDTO request) {
        task.setTitle(request.title().strip());
        task.setNote(normaliseNote(request.note()));
        task.setTaskDate(request.date());
        task.setTaskTime(minutesOf(request.time()));
        task.setAdvanceMinutes(advanceOf(request));
    }

    /** Minutes, like every time the clients offer; a seconds field would only shift the reminder. */
    private static LocalTime minutesOf(LocalTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
    }

    private static int advanceOf(CalendarTaskRequestDTO request) {
        return request.advanceMinutes() == null ? 0 : request.advanceMinutes();
    }

    /** A note of nothing but whitespace is no note, so "has a note" is one null check. */
    private static String normaliseNote(String note) {
        if (note == null) {
            return null;
        }
        String stripped = note.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    /**
     * Microseconds, which is what a timestamptz keeps. Truncating here means the value in memory
     * is the value stored, so the reminder job's "was this edited since I read it" comparison on
     * {@code updated_at} never trips over a nanosecond the database rounded away.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
