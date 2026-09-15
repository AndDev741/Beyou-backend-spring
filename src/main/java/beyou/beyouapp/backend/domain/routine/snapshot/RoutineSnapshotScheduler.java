package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.DayCloseService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.monitoring.SnapshotJobHeartbeat;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoutineSnapshotScheduler {

    private static final int MAX_BACKFILL_DAYS = 7;

    /**
     * The first local hour at which the previous day is closed out (KTD18). Deliberately not
     * midnight: a check committing at 23:59:59.9 has to be allowed to land before anything
     * declares the day over, and the snapshot cycle is already doing its own work at hour 0.
     * The grace also means the two branches never run in the same pass for the same
     * timezone, so a snapshot failure cannot take the day-close down with it.
     *
     * <p>The close branch fires across this hour <em>and the next</em> — see the window at
     * the call site, and why an equality test loses the spring-forward day outright.
     */
    private static final int DAY_CLOSE_GRACE_HOUR = 2;

    private final UserRepository userRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final RoutineSnapshotRepository snapshotRepository;
    private final SnapshotService snapshotService;
    private final SnapshotCheckMigrator checkMigrator;
    private final SnapshotJobHeartbeat heartbeat;
    private final DayCloseService dayCloseService;
    private final EntityCheckDayRepository entityCheckDayRepository;
    private final UserCacheEvictService userCacheEvictService;

    /**
     * Self-reference injected lazily to allow calling @Transactional methods
     * through the Spring proxy (self-invocations bypass AOP proxying).
     */
    @Lazy
    @Autowired
    private RoutineSnapshotScheduler self;

    /**
     * The clock {@link #processSnapshots()} reads to decide what hour it is in each
     * timezone. Deliberately not a constructor parameter — it has no bean to inject and the
     * constructor is the injection point for eight real collaborators. Tests replace it the
     * same way they replace {@link #self}.
     *
     * <p>Only the hour-gating below goes through it. {@link #backfillMissedSnapshots()} does
     * not: it runs once at boot with no hour condition, so there is nothing there a fixed
     * clock would pin.
     */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * Runs once on startup — detects missed snapshots and backfills up to 7 days.
     * Backfilled snapshots use the CURRENT routine structure (historical structure
     * is not recoverable). This is an accepted trade-off documented in the spec.
     */
    /**
     * Runs once on startup. Computes which dates need backfilling per user,
     * then delegates to createSnapshotsForUser (which is @Transactional and
     * public, so the Spring proxy provides a Hibernate session).
     *
     * <p>Deliberately does NOT close days (U5, KTD19). This walks seven days on every boot,
     * and closing them retroactively would stamp MISSED on days a habit did not exist for or
     * a routine was not yet scheduled on — inventing failures out of downtime. A day the
     * close pass never reached simply carries no row, which the streak walk reads as unknown
     * and steps over. Missing history is honest; fabricated history is not.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(1)
    public void backfillMissedSnapshots() {
        log.info("Starting startup backfill for missed snapshots");

        List<User> allUsers = userRepository.findAll();
        for (User user : allUsers) {
            try {
                ZoneId zoneId = ZoneId.of(user.getTimezone());
                LocalDate userToday = LocalDate.now(zoneId);
                LocalDate yesterday = userToday.minusDays(1);
                LocalDate earliestAllowed = yesterday.minusDays(MAX_BACKFILL_DAYS - 1);

                // Iterate each day in the backfill window and call the
                // @Transactional createSnapshotsForUser for each date.
                // That method already handles schedule checks, duplicate
                // prevention, and lazy-loaded collections within a session.
                for (LocalDate date = earliestAllowed; !date.isAfter(yesterday); date = date.plusDays(1)) {
                    try {
                        self.createSnapshotsForUser(user, date);
                    } catch (Exception e) {
                        log.error("Failed to backfill date {} for user {}", date, user.getId(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to backfill snapshots for user {}", user.getId(), e);
            }
        }

        log.info("Startup backfill completed");
    }


    /**
     * Closes the days whose grace hour passed while this service was not running.
     *
     * <p>{@link #backfillMissedSnapshots()} rebuilds the snapshots a downtime cost, but a
     * snapshot is only half of a day. The outcome rows and the streak recompute come from
     * {@link DayCloseService#closeDay}, fired from a two-hour window in
     * {@link #processSnapshots()} that comes round once per local day. Miss that window and
     * the day stays half closed for good: the live check-ins sit there, the account-level
     * row never arrives, and the streak stops at the day before. A thirteen-hour host
     * shutdown on 2026-09-14 did precisely that to every user in Europe/Lisbon,
     * Europe/London and UTC, while America/Sao_Paulo came through intact because its window
     * happened to fall after the machine was back.
     *
     * <p>Until this existed there was no way back. Nothing outside the scheduler calls
     * {@code closeDay}, the backfill deliberately does not close anything, and the window is
     * a single shot per local day.
     *
     * <p>Safe on every boot. {@code closeDay} inserts with ON CONFLICT DO NOTHING and diffs
     * against what is already recorded, so a day that is already closed costs one read and
     * writes nothing. It also floors each owner at its own {@code createdAt}, so a habit
     * created after the day being closed is never stamped MISSED for it.
     *
     * <p>The window is the seven days the snapshot backfill walks, for the sake of the two
     * agreeing. That pass already accepts that a rebuilt day is judged by the CURRENT
     * routine structure, the historical one being unrecoverable; closing those same days
     * against the current schedule is that same accepted trade-off. Two passes disagreeing
     * about which days exist would be worse than either one's rough edges.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    public void closeDaysMissedWhileDown() {
        log.info("Starting startup close for days missed while the service was down");

        int daysClosed = 0;
        for (User user : userRepository.findAll()) {
            try {
                ZoneId zoneId = ZoneId.of(user.getTimezone());
                ZonedDateTime nowInZone = ZonedDateTime.now(clock.withZone(zoneId));

                LocalDate latestClosable = latestClosableDay(nowInZone);
                LocalDate earliest = latestClosable.minusDays(MAX_BACKFILL_DAYS - 1L);

                Set<LocalDate> alreadyClosed = entityCheckDayRepository
                        .findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), earliest, latestClosable)
                        .stream()
                        .filter(row -> row.getOwnerType() == CheckDayOwnerType.USER)
                        .map(EntityCheckDay::getDay)
                        .collect(Collectors.toSet());

                for (LocalDate day = earliest; !day.isAfter(latestClosable); day = day.plusDays(1)) {
                    // The account-level row is the marker: closeDay writes one for every day
                    // it closes, so its absence is what "this day never closed" looks like.
                    if (alreadyClosed.contains(day)) {
                        continue;
                    }
                    try {
                        if (dayCloseService.closeDay(user, day) > 0) {
                            daysClosed++;
                            log.info("Closed day {} for user {}, missed while the service was down",
                                    day, user.getId());
                        }
                    } catch (Exception e) {
                        log.error("Failed to close missed day {} for user {}", day, user.getId(), e);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to close missed days for user {}", user.getId(), e);
            }
        }

        // Once for the whole sweep, for the same reason closeYesterdayForTimezone does it
        // once per batch: the routine cache is keyed userId_routineId and clears wholesale.
        if (daysClosed > 0) {
            userCacheEvictService.clearSharedRoutineCache();
        }

        log.info("Startup close completed, {} days closed", daysClosed);
    }

    /**
     * The most recent day whose close is already due in this zone.
     *
     * <p>A day is closed at {@link #DAY_CLOSE_GRACE_HOUR} of the day AFTER it, so before
     * that hour strikes, yesterday's close is not late, it is merely not due. Stamping it
     * early would cut short a day the user can still be checking into, which is the one
     * thing this pass must never do.
     */
    private static LocalDate latestClosableDay(ZonedDateTime nowInZone) {
        LocalDate today = nowInZone.toLocalDate();
        return nowInZone.getHour() >= DAY_CLOSE_GRACE_HOUR
                ? today.minusDays(1)
                : today.minusDays(2);
    }

    /**
     * Runs at the top of every hour. Most hours are a no-op — the cycle acts only on the
     * timezones whose local clock has just crossed midnight (snapshots) or reached the
     * day-close grace hour (absence rows) — but it runs hourly regardless, which is what
     * makes it usable as a liveness signal.
     *
     * <p>On completion it checks in with the collector (see {@link SnapshotJobHeartbeat}).
     * The collector's monitor alerts when a check-in fails to ARRIVE, which is the only
     * way to learn that this job stopped running: a wedged scheduler thread leaves
     * {@code /actuator/health} answering 200 while snapshots quietly stop being written.
     * Hourly, rather than only on the midnight branch, so detection is measured in hours
     * instead of a day.
     *
     * <p>Scope of the signal: it means "the cycle ran to completion", not "every user's
     * snapshot was written". The per-timezone and per-user failures below stay isolated
     * and logged, deliberately — one user with an unparseable timezone must not blind you
     * to whether the job itself is alive. Those failures surface as ERROR logs.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void processSnapshots() {
        log.info("Starting snapshot processing cycle");

        List<String> timezones = userRepository.findDistinctTimezones();

        for (String timezone : timezones) {
            try {
                ZoneId zoneId = ZoneId.of(timezone);
                ZonedDateTime nowInZone = ZonedDateTime.now(clock.withZone(zoneId));

                if (nowInZone.getHour() == 0) {
                    // It's midnight in this timezone — snapshot yesterday's data
                    LocalDate yesterday = nowInZone.toLocalDate().minusDays(1);
                    List<User> users = userRepository.findAllByTimezone(timezone);

                    log.info("Midnight detected for timezone {}, processing {} users", timezone, users.size());

                    for (User user : users) {
                        try {
                            self.createSnapshotsForUser(user, yesterday);
                        } catch (Exception e) {
                            log.error("Failed to create snapshots for user {} in timezone {}",
                                    user.getId(), timezone, e);
                        }
                    }
                }

                // A window, not an equality. On a spring-forward day the local clock jumps
                // straight from 01:59 to 03:00, so an hour inside the jump is never
                // observed — America/New_York, CET and most other DST zones skip 02:xx
                // entirely on their changeover date, and an `== DAY_CLOSE_GRACE_HOUR`
                // trigger left that day permanently unclosed for every user in the zone.
                // Widening by an hour costs one extra sweep on ordinary days, which is a
                // no-op: closeDay diffs against the rows already recorded and its insert is
                // ON CONFLICT DO NOTHING.
                if (nowInZone.getHour() >= DAY_CLOSE_GRACE_HOUR
                        && nowInZone.getHour() <= DAY_CLOSE_GRACE_HOUR + 1) {
                    closeYesterdayForTimezone(timezone, nowInZone.toLocalDate().minusDays(1));
                }
            } catch (Exception e) {
                log.error("Failed to process timezone {}", timezone, e);
            }
        }

        log.info("Snapshot processing cycle completed");

        // Last statement on purpose. Anything that escapes the loop above (the timezone
        // query failing, for instance) propagates before this line and leaves the
        // collector waiting — which is exactly the alert we want. A signal on entry, or
        // in a finally block, would report "the job is fine" for a job that just died.
        signalHeartbeat();
    }

    /**
     * Stamps an outcome on every owner that finished {@code closingDay} without one, for
     * every user in this timezone (R5, U5).
     *
     * <p>A sibling of the midnight snapshot branch rather than a step inside
     * {@code createSnapshotsForUser}: that method returns early for a user with no routines,
     * and such a user still needs an account-level row saying so. The day-close is also
     * per-user-per-date and not per-routine, so a habit sitting in two routines is visited
     * once.
     *
     * <p>The per-user try/catch is the same shape as the snapshot loop above and matters for
     * the same reason: an exception escaping here would skip {@link #signalHeartbeat()} and
     * trip the snapshot-job-dead monitor for something that is not the snapshot job.
     *
     * <p>{@code DayCloseService} is a separate {@code @Transactional} bean, so each call
     * already crosses its own Spring proxy and gets its own transaction — one user's failure
     * rolls back that user's day and nothing else. The {@code @Lazy self} hop the snapshot
     * branch needs is only there because {@code createSnapshotsForUser} lives on this class.
     */
    private void closeYesterdayForTimezone(String timezone, LocalDate closingDay) {
        List<User> users = userRepository.findAllByTimezone(timezone);
        log.info("Day-close grace hour reached for timezone {}, closing {} for {} users",
                timezone, closingDay, users.size());

        int usersClosed = 0;
        for (User user : users) {
            try {
                if (dayCloseService.closeDay(user, closingDay) > 0) {
                    usersClosed++;
                }
            } catch (Exception e) {
                log.error("Failed to close day {} for user {} in timezone {}",
                        closingDay, user.getId(), timezone, e);
            }
        }

        // Once for the whole batch, not once per user. The `routine` cache is keyed
        // userId_routineId and can only be cleared wholesale, so calling
        // evictAllUserCaches inside the loop would flush it once per user.
        if (usersClosed > 0) {
            userCacheEvictService.clearSharedRoutineCache();
        }
    }

    /**
     * The heartbeat already swallows delivery failures; this guards the remaining
     * surface (a misconfiguration or bug inside the signal path itself). Monitoring must
     * never be the reason the snapshot job fails.
     */
    private void signalHeartbeat() {
        try {
            heartbeat.signalCycleCompleted();
        } catch (Exception e) {
            log.error("Snapshot job heartbeat signalling failed", e);
        }
    }

    @Transactional
    public void createSnapshotsForUser(User user, LocalDate date) {
        log.debug("Creating snapshots for user {} on date {}", user.getId(), date);

        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(user.getId());

        if (routines.isEmpty()) {
            log.debug("No routines found for user {}", user.getId());
            return;
        }

        WeekDay weekDay = ScheduledOnDayResolver.weekDayOf(date);

        for (DiaryRoutine routine : routines) {
            // Same predicate the day-close pass uses, so the two can never disagree
            // about whether a routine ran on a given day.
            if (!ScheduledOnDayResolver.coversDay(routine, date)) {
                log.debug("Routine {} not scheduled for {}", routine.getId(), weekDay);
                continue;
            }

            // Check if snapshot already exists (duplicate prevention)
            boolean exists = snapshotRepository
                    .findByRoutineIdAndSnapshotDate(routine.getId(), date)
                    .isPresent();

            if (exists) {
                log.debug("Snapshot already exists for routine {} on {}", routine.getId(), date);
                continue;
            }

            // Create snapshot and migrate checks atomically —
            // if migrateChecks fails, the whole transaction rolls back
            // so we don't end up with a snapshot missing its check data.
            RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, user, date);
            checkMigrator.migrateChecks(routine, snapshot, date);

            log.info("Snapshot created for routine {} on date {}", routine.getId(), date);
        }
    }
}
