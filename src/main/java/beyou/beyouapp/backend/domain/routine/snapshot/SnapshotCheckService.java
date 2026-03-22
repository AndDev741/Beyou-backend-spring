package beyou.beyouapp.backend.domain.routine.snapshot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
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

        recalculateCompleted(snapshot, user);
        snapshotCheckRepository.save(check);
        snapshotRepository.save(snapshot);

        return refreshUiDtoBuilder.buildSnapshotRefreshUiDto(user);
    }

    private void checkSnapshotItem(User user, DiaryRoutine routine, RoutineSnapshot snapshot, SnapshotCheck check) {
        double baseXp = 10.0 * check.getDifficulty() * check.getImportance();

        LocalDate userLocalDate = LocalDate.now(ZoneId.of(user.getTimezone()));
        double decayedXp = xpDecayCalculator.calculateDecayedXp(
                baseXp, user.getXpDecayStrategy(), snapshot.getSnapshotDate(), userLocalDate);

        check.setChecked(true);
        check.setSkipped(false);
        check.setCheckTime(LocalTime.now());
        check.setXpGenerated(decayedXp);

        applyXp(user, routine, check, decayedXp, true);
    }

    private void uncheckSnapshotItem(User user, DiaryRoutine routine, RoutineSnapshot snapshot, SnapshotCheck check) {
        double storedXp = check.getXpGenerated();

        check.setChecked(false);
        check.setCheckTime(null);
        check.setXpGenerated(0.0);

        if (storedXp > 0.0) {
            applyXp(user, routine, check, storedXp, false);
        }
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
            userService.unmarkDayComplete(user, snapshotDate);
        }
    }
}
