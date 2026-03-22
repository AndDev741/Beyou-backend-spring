package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
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
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoutineSnapshotScheduler {

    private static final int MAX_BACKFILL_DAYS = 7;

    private final UserRepository userRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final RoutineSnapshotRepository snapshotRepository;
    private final SnapshotService snapshotService;
    private final SnapshotCheckMigrator checkMigrator;

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
            } catch (Exception e) {
                log.error("Failed to process timezone {}", timezone, e);
            }
        }

        log.info("Snapshot processing cycle completed");
    }

    @Transactional
    public void createSnapshotsForUser(User user, LocalDate date) {
        log.debug("Creating snapshots for user {} on date {}", user.getId(), date);

        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(user.getId());

        if (routines.isEmpty()) {
            log.debug("No routines found for user {}", user.getId());
            return;
        }

        // Convert date's day of week to WeekDay enum
        String dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        WeekDay weekDay = WeekDay.valueOf(dayName);

        for (DiaryRoutine routine : routines) {
            // Check if routine is scheduled for this day
            if (routine.getSchedule() == null
                    || routine.getSchedule().getDays() == null
                    || !routine.getSchedule().getDays().contains(weekDay)) {
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
