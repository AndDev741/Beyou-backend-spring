package beyou.beyouapp.backend.domain.routine.snapshot;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.*;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
     * Runs once on startup — detects missed snapshots and backfills up to 7 days.
     * Backfilled snapshots use the CURRENT routine structure (historical structure
     * is not recoverable). This is an accepted trade-off documented in the spec.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissedSnapshots() {
        log.info("Starting startup backfill for missed snapshots");

        List<User> allUsers = userRepository.findAll();
        for (User user : allUsers) {
            try {
                backfillForUser(user);
            } catch (Exception e) {
                log.error("Failed to backfill snapshots for user {}", user.getId(), e);
            }
        }

        log.info("Startup backfill completed");
    }

    private void backfillForUser(User user) {
        ZoneId zoneId = ZoneId.of(user.getTimezone());
        LocalDate userToday = LocalDate.now(zoneId);
        // Yesterday is the most recent day that could need a snapshot
        LocalDate yesterday = userToday.minusDays(1);

        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(user.getId());
        if (routines.isEmpty()) return;

        for (DiaryRoutine routine : routines) {
            if (routine.getSchedule() == null || routine.getSchedule().getDays() == null) continue;

            try {
                // Find the last snapshot date for this routine
                Optional<LocalDate> latestOpt = snapshotRepository.findLatestSnapshotDateByRoutineId(routine.getId());
                // Start from the day after the last snapshot, or MAX_BACKFILL_DAYS ago
                LocalDate startDate = latestOpt
                        .map(latest -> latest.plusDays(1))
                        .orElse(yesterday.minusDays(MAX_BACKFILL_DAYS - 1));

                // Don't go further back than MAX_BACKFILL_DAYS
                LocalDate earliestAllowed = yesterday.minusDays(MAX_BACKFILL_DAYS - 1);
                if (startDate.isBefore(earliestAllowed)) {
                    startDate = earliestAllowed;
                }

                // Backfill each missing day
                for (LocalDate date = startDate; !date.isAfter(yesterday); date = date.plusDays(1)) {
                    String dayName = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                    WeekDay weekDay = WeekDay.valueOf(dayName);

                    if (!routine.getSchedule().getDays().contains(weekDay)) continue;
                    if (snapshotRepository.findByRoutineIdAndSnapshotDate(routine.getId(), date).isPresent()) continue;

                    RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, user, date);
                    checkMigrator.migrateChecks(routine, snapshot, date);
                    log.info("Backfilled snapshot for routine {} on date {}", routine.getId(), date);
                }
            } catch (Exception e) {
                log.error("Failed to backfill routine {} for user {}", routine.getId(), user.getId(), e);
            }
        }
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
                            createSnapshotsForUser(user, yesterday);
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
            try {
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

                // Create snapshot and migrate checks
                RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, user, date);
                checkMigrator.migrateChecks(routine, snapshot, date);

                log.info("Snapshot created for routine {} on date {}", routine.getId(), date);
            } catch (Exception e) {
                log.error("Failed to create snapshot for routine {} on date {}",
                        routine.getId(), date, e);
            }
        }
    }
}
