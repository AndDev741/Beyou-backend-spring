package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.checkday.DayCloseService;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.*;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoutineSnapshotScheduler {

    private static final int MAX_BACKFILL_DAYS = 7;

    /**
     * The local hour at which the previous day is closed out (KTD18). Deliberately not
     * midnight: a check committing at 23:59:59.9 has to be allowed to land before anything
     * declares the day over, and the snapshot cycle is already doing its own work at hour 0.
     * The grace also means the two branches never run in the same pass for the same
     * timezone, so a snapshot failure cannot take the day-close down with it.
     */
    private static final int DAY_CLOSE_GRACE_HOUR = 2;

    private final UserRepository userRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final RoutineSnapshotRepository snapshotRepository;
    private final SnapshotService snapshotService;
    private final SnapshotCheckMigrator checkMigrator;
    private final SnapshotJobHeartbeat heartbeat;
    private final DayCloseService dayCloseService;
    private final UserCacheEvictService userCacheEvictService;

    /**
     * Self-reference injected lazily to allow calling @Transactional methods
     * through the Spring proxy (self-invocations bypass AOP proxying).
     */
    @Lazy
    @Autowired
    private RoutineSnapshotScheduler self;

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
                ZonedDateTime nowInZone = ZonedDateTime.now(zoneId);

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

                if (nowInZone.getHour() == DAY_CLOSE_GRACE_HOUR) {
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
