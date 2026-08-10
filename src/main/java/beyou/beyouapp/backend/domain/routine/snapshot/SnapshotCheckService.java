package beyou.beyouapp.backend.domain.routine.snapshot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckDayRecorder;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.CheckXpCalculator;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver.Standing;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SnapshotCheckService {

    private final RoutineSnapshotRepository snapshotRepository;
    private final SnapshotCheckRepository snapshotCheckRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final HabitRepository habitRepository;
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final XpCalculatorService xpCalculatorService;
    private final XpDecayCalculator xpDecayCalculator;
    private final RefreshUiDtoBuilder refreshUiDtoBuilder;
    private final AuthenticatedUser authenticatedUser;
    private final CheckDayRecorder checkDayRecorder;
    private final UserCacheEvictService userCacheEvictService;

    /**
     * How a snapshot item stood on its own snapshot date.
     *
     * <p>A snapshot exists for a date only because the routine covered it, and this check row
     * exists only because the item sat in that routine at the time — so the snapshot is its
     * own schedule evidence, and an unchecked past day means {@code MISSED}. Asking the
     * <em>current</em> schedule instead would derive a past outcome from a later schedule
     * state, which R7 forbids: a routine edited last week would silently rewrite what
     * happened the month before.
     */
    private static final Standing SCHEDULED_THAT_DAY = new Standing(true, true);

    @Transactional
    public RefreshUiDTO checkOrUncheckSnapshotItem(UUID snapshotId, UUID snapshotCheckId) {
        User user = authenticatedUser.getAuthenticatedUser();

        RoutineSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new BusinessException(ErrorKey.SNAPSHOT_NOT_FOUND,
                        "Snapshot not found with id " + snapshotId));

        if (!snapshot.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorKey.SNAPSHOT_NOT_OWNED,
                    "User does not own the requested snapshot");
        }

        SnapshotCheck check = snapshotCheckRepository.findById(snapshotCheckId)
                .orElseThrow(() -> new BusinessException(ErrorKey.SNAPSHOT_CHECK_NOT_FOUND,
                        "Snapshot check not found with id " + snapshotCheckId));

        if (!check.getSnapshot().getId().equals(snapshotId)) {
            throw new BusinessException(ErrorKey.SNAPSHOT_CHECK_NOT_IN_SNAPSHOT,
                    "Snapshot check does not belong to the specified snapshot");
        }

        DiaryRoutine routine = diaryRoutineRepository.findById(snapshot.getRoutine().getId())
                .orElse(null);

        if (check.isChecked()) {
            uncheckSnapshotItem(user, routine, snapshot, check);
        } else {
            checkSnapshotItem(user, routine, snapshot, check);
        }

        recalculateCompleted(snapshot, user);
        snapshotCheckRepository.save(check);
        snapshotRepository.save(snapshot);
        userRepository.save(user);

        // R16 — this path moves habit XP, habit levels and now habit streak scalars, all of
        // which the 30-minute `habits` cache serves. Without this the user edits a past day
        // and watches the old numbers for half an hour.
        userCacheEvictService.evictAllUserCaches(user.getId());

        return refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user);
    }

    @Transactional
    public RefreshUiDTO skipOrUnskipSnapshotItem(UUID snapshotId, UUID snapshotCheckId) {
        User user = authenticatedUser.getAuthenticatedUser();

        RoutineSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new BusinessException(ErrorKey.SNAPSHOT_NOT_FOUND,
                        "Snapshot not found with id " + snapshotId));

        if (!snapshot.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorKey.SNAPSHOT_NOT_OWNED,
                    "User does not own the requested snapshot");
        }

        SnapshotCheck check = snapshotCheckRepository.findById(snapshotCheckId)
                .orElseThrow(() -> new BusinessException(ErrorKey.SNAPSHOT_CHECK_NOT_FOUND,
                        "Snapshot check not found with id " + snapshotCheckId));

        if (!check.getSnapshot().getId().equals(snapshotId)) {
            throw new BusinessException(ErrorKey.SNAPSHOT_CHECK_NOT_IN_SNAPSHOT,
                    "Snapshot check does not belong to the specified snapshot");
        }

        // If item is already checked, no-op
        if (check.isChecked()) {
            return refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user);
        }

        // Toggle skipped flag
        check.setSkipped(!check.isSkipped());

        // R12 — a deliberate skip keeps the day out of the MISSED column, so the streak walks
        // straight through it. Un-skipping hands the day back to its absence outcome.
        recordSnapshotDay(user, snapshot, check,
                check.isSkipped() ? CheckDayOutcome.SKIPPED : absenceOutcome(user, snapshot));

        recalculateCompleted(snapshot, user);
        snapshotCheckRepository.save(check);
        snapshotRepository.save(snapshot);

        userCacheEvictService.evictAllUserCaches(user.getId());

        return refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user);
    }

    private void checkSnapshotItem(User user, DiaryRoutine routine, RoutineSnapshot snapshot, SnapshotCheck check) {
        // ponytail: no streak bonus on late check-ins (a late check already broke the streak);
        // decay still applies below. Snapshot has no constance to read anyway.
        double baseXp = CheckXpCalculator.calculate(check.getDifficulty(), check.getImportance(), 0);

        LocalDate userLocalDate = LocalDate.now(ZoneId.of(user.getTimezone()));
        double decayedXp = xpDecayCalculator.calculateDecayedXp(
                baseXp, user.getXpDecayStrategy(), snapshot.getSnapshotDate(), userLocalDate);

        check.setChecked(true);
        check.setSkipped(false);
        check.setCheckTime(LocalTime.now());
        check.setXpGenerated(decayedXp);

        applyXp(user, routine, check, decayedXp, true);
        recordSnapshotDay(user, snapshot, check, CheckDayOutcome.DONE);
    }

    private void uncheckSnapshotItem(User user, DiaryRoutine routine, RoutineSnapshot snapshot, SnapshotCheck check) {
        double storedXp = check.getXpGenerated();

        check.setChecked(false);
        check.setCheckTime(null);
        check.setXpGenerated(0.0);

        if (storedXp > 0.0) {
            applyXp(user, routine, check, storedXp, false);
        }
        recordSnapshotDay(user, snapshot, check, absenceOutcome(user, snapshot));
    }

    /**
     * Stamps the snapshot's own day on the item behind the check and re-derives its scalars.
     *
     * <p>KTD8 — a user editing a past day through this endpoint is correcting the record on
     * purpose, so overwriting that day's row is sanctioned. What is forbidden is a background
     * process re-deriving an old outcome from a newer schedule, which is why the outcome here
     * comes from the edit itself and from {@link #SCHEDULED_THAT_DAY}, never from a fresh read
     * of the routine's current schedule.
     *
     * <p>Nothing is written when the original habit or task is gone: the row would be history
     * for an owner that no longer exists, unreadable by every endpoint and unreachable by the
     * delete cascade that is supposed to clean it up. The XP fallback in {@link #applyXp}
     * already covers that case for the numbers the user can see.
     */
    private void recordSnapshotDay(User user, RoutineSnapshot snapshot, SnapshotCheck check,
                                   CheckDayOutcome outcome) {
        UUID originalItemId = check.getOriginalItemId();
        if (originalItemId == null || check.getItemType() == null) {
            return;
        }
        LocalDate day = snapshot.getSnapshotDate();

        if (check.getItemType() == SnapshotItemType.HABIT) {
            habitRepository.findById(originalItemId).ifPresent(habit ->
                    writeDay(user, CheckDayOwnerType.HABIT, habit.getId(),
                            habit.getCheckProgress(), day, outcome));
            return;
        }

        taskRepository.findById(originalItemId).ifPresent(task -> {
            // R4/KTD14, same as the live path: a one-time task is checked once and deleted the
            // day after, so a streak over it could never be extended by anything.
            if (task.isOneTimeTask()) {
                return;
            }
            writeDay(user, CheckDayOwnerType.TASK, task.getId(),
                    task.getCheckProgress(), day, outcome);
        });
    }

    private void writeDay(User user, CheckDayOwnerType ownerType, UUID ownerId,
                          CheckProgress progress, LocalDate day, CheckDayOutcome outcome) {
        if (outcome == null) {
            checkDayRecorder.clearDay(user, ownerType, ownerId, progress, day);
            return;
        }
        checkDayRecorder.record(user, ownerType, ownerId, progress, day, outcome);
    }

    /**
     * What a snapshot day means once its check is taken away.
     *
     * <p>Snapshots are only ever written for a day that has already ended, so this resolves to
     * {@code MISSED} for everything this endpoint can reach. The day is still tested rather
     * than assumed because the rule it protects is not obvious: on a day still running,
     * "scheduled and left unchecked" is not yet true, so the day is returned to unknown
     * instead of being stamped — which is exactly what the live check path avoids doing at
     * 09:00. See {@code CheckDayRecorder.absenceOutcome}.
     */
    private CheckDayOutcome absenceOutcome(User user, RoutineSnapshot snapshot) {
        boolean dayClosed = snapshot.getSnapshotDate().isBefore(UserDateResolver.today(user));
        return CheckDayRecorder.absenceOutcome(SCHEDULED_THAT_DAY, dayClosed);
    }

    private void applyXp(User user, DiaryRoutine routine, SnapshotCheck check, double xp, boolean add) {
        if (xp == 0.0) return;

        // If routine still exists, try full XP distribution
        if (routine != null) {
            UUID originalItemId = check.getOriginalItemId();

            if (originalItemId != null && check.getItemType() == SnapshotItemType.HABIT) {
                Optional<Habit> habitOpt = habitRepository.findById(originalItemId);
                if (habitOpt.isPresent()) {
                    Habit habit = habitOpt.get();
                    if (add) {
                        xpCalculatorService.addXpToUserRoutineHabitAndCategoriesAndPersist(
                                user, xp, routine, habit, habit.getCategories());
                    } else {
                        xpCalculatorService.removeXpOfUserRoutineHabitAndCategoriesAndPersist(
                                user, xp, routine, habit, habit.getCategories());
                    }
                    return;
                }
            } else if (originalItemId != null && check.getItemType() == SnapshotItemType.TASK) {
                Optional<Task> taskOpt = taskRepository.findById(originalItemId);
                if (taskOpt.isPresent()) {
                    Task task = taskOpt.get();
                    if (add) {
                        xpCalculatorService.addXpToUserRoutineAndCategoriesAndPersist(
                                user, xp, routine, task.getCategories());
                    } else {
                        xpCalculatorService.removeXpOfUserRoutineAndCategoriesAndPersist(
                                user, xp, routine, task.getCategories());
                    }
                    return;
                }
            }

            // Fallback: original item deleted but routine exists
            if (add) {
                xpCalculatorService.addXpToUserAndRoutineOnly(user, xp, routine);
            } else {
                xpCalculatorService.removeXpFromUserAndRoutineOnly(user, xp, routine);
            }
        } else {
            // Routine deleted — apply XP to user only
            if (add) {
                xpCalculatorService.addXpToUserOnly(user, xp);
            } else {
                xpCalculatorService.removeXpFromUserOnly(user, xp);
            }
        }
    }

    private void recalculateCompleted(RoutineSnapshot snapshot, User user) {
        List<SnapshotCheck> checks = snapshot.getChecks();
        boolean completed = switch (user.getConstanceConfiguration()) {
            case COMPLETE -> checks.stream().allMatch(c -> c.isChecked() || c.isSkipped());
            case ANY -> checks.stream().anyMatch(SnapshotCheck::isChecked);
        };

        boolean wasCompleted = snapshot.isCompleted();
        snapshot.setCompleted(completed);

        // Update user constance streak for the snapshot date
        LocalDate snapshotDate = snapshot.getSnapshotDate();
        if (completed && !wasCompleted) {
            userService.markDayCompleted(user, snapshotDate);
        } else if (!completed && wasCompleted) {
            // Only unmark if no other snapshot for this user+date is still complete
            boolean otherSnapshotsComplete = snapshotRepository
                    .existsByUserIdAndSnapshotDateAndCompletedTrue(user.getId(), snapshotDate);
            if (!otherSnapshotsComplete) {
                userService.unmarkDayComplete(user, snapshotDate);
            }
        }
    }
}
