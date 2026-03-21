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

import java.time.*;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class RoutineSnapshotScheduler {

    private final UserRepository userRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final RoutineSnapshotRepository snapshotRepository;
    private final SnapshotService snapshotService;
    private final SnapshotCheckMigrator checkMigrator;

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
