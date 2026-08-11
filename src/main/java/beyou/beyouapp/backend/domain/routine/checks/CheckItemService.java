package beyou.beyouapp.backend.domain.routine.checks;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
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
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshItemCheckedDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class CheckItemService {

    private final ItemGroupService itemGroupService;
    private final XpCalculatorService xpCalculatorService;
    private final UserService userService;
    private final RefreshUiDtoBuilder refreshUiDtoBuilder;
    private final CheckDayRecorder checkDayRecorder;

    @Transactional
    public RefreshUiDTO checkOrUncheckItemGroup(CheckGroupRequestDTO checkGroupDTO) {
        // The day is resolved only once the group (and with it its owner) is in hand: a check
        // row is permanent history, so it must carry the owner's local day, not the server's.
        if(checkGroupDTO.habitGroupDTO() != null){
            HabitGroup habitGroup = itemGroupService.findHabitGroupByDTO(checkGroupDTO.routineId(), checkGroupDTO.habitGroupDTO().habitGroupId());
            return checkOrUncheckHabitGroup(habitGroup, ownerToday(habitGroup.getRoutineSection().getRoutine()));
        }else if(checkGroupDTO.taskGroupDTO() != null){
            TaskGroup taskGroup = itemGroupService.findTaskGroupByDTO(checkGroupDTO.routineId(), checkGroupDTO.taskGroupDTO().taskGroupId());
            return checkOrUncheckTaskGroup(taskGroup, ownerToday(taskGroup.getRoutineSection().getRoutine()));
        }else{
            throw new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED, "No Item group found in the request");
        }
    }

    @Transactional
    public RefreshUiDTO skipOrUnskipItemGroup(SkipGroupRequestDTO skipGroupDTO) {
        if(skipGroupDTO.habitGroupDTO() != null){
            HabitGroup habitGroup = itemGroupService.findHabitGroupByDTO(skipGroupDTO.routineId(), skipGroupDTO.habitGroupDTO().habitGroupId());
            LocalDate date = requireNotInTheFuture(
                skipGroupDTO.date(), habitGroup.getRoutineSection().getRoutine());
            if (isHabitGroupChecked(habitGroup, date)) {
                return buildNoOpRefresh(habitGroup.getId(), getHabitGroupChecked(habitGroup, date), date, habitGroup.getRoutineSection().getRoutine());
            }
            return skipGroupDTO.skip()
                ? skipHabitGroup(habitGroup, date)
                : unskipHabitGroup(habitGroup, date);
        }else if(skipGroupDTO.taskGroupDTO() != null){
            TaskGroup taskGroup = itemGroupService.findTaskGroupByDTO(skipGroupDTO.routineId(), skipGroupDTO.taskGroupDTO().taskGroupId());
            LocalDate date = requireNotInTheFuture(
                skipGroupDTO.date(), taskGroup.getRoutineSection().getRoutine());
            if (isTaskGroupChecked(taskGroup, date)) {
                return buildNoOpRefresh(taskGroup.getId(), getTaskGroupChecked(taskGroup, date), date, taskGroup.getRoutineSection().getRoutine());
            }
            return skipGroupDTO.skip()
                ? skipTaskGroup(taskGroup, date)
                : unskipTaskGroup(taskGroup, date);
        }else{
            throw new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED, "No Item group found in the request");
        }
    }

    /**
     * Today in the routine owner's timezone. Identity travels with the data (the routine's
     * owner), not a ThreadLocal: agent tools run on a boundedElastic thread with no
     * SecurityContext, so the security context is never consulted here.
     */
    private LocalDate ownerToday(Routine routine) {
        return UserDateResolver.today(routine.getUser());
    }

    /**
     * The date a skip will be written at: the one the request asked for, or the owner's
     * today when it asked for none — and never a day that has not happened yet.
     *
     * <p>The skip path is the only one that honours a client-supplied date, and what it
     * writes is permanent: a {@code SKIPPED} row in {@code entity_check_day}. The day-close
     * pass is insert-only, so a row planted on a future day survives that day arriving —
     * the real {@code MISSED} never lands, and a habit skipped forward over a range has a
     * streak that can never break. That is the unbounded streak the {@code constance}
     * retirement was meant to end, except it now buys a capped +50% XP bonus on every
     * check. The agent tool {@code Tools.skipRoutineItem} reaches this same method, which is
     * why the bound is here and not in {@code RoutineController}.
     *
     * <p>The bound is the <em>owner's</em> today, resolved in the owner's zone (R15), not
     * the server's — a user fourteen hours ahead is entitled to their own date.
     *
     * <p>No lower bound, deliberately. A back-dated skip is how a user corrects a day they
     * really did skip, {@code SKIPPED} is streak-neutral (R12) and awards no XP, and no
     * floor is available at this call site that would not be arbitrary — {@code Routine}
     * carries no {@code created_at}. One thing that bound would have caught and this one
     * does not: {@code CheckDayRecorder.record} overwrites the day's existing row, so
     * skipping a past day that closed as {@code MISSED} rewrites it to {@code SKIPPED} and
     * a streak broken weeks ago walks straight through it again. Deciding how far back a
     * user may edit their own history is a product question, not a bound to invent here.
     *
     * @throws BusinessException {@code INVALID_REQUEST} when the date is after the owner's
     *                           today
     */
    private LocalDate requireNotInTheFuture(LocalDate requested, Routine routine) {
        LocalDate today = ownerToday(routine);
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today)) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "A routine item cannot be skipped on a day that has not happened yet");
        }
        return requested;
    }

    /**
     * Stamps the day's outcome on the habit and re-derives its scalars from the rows.
     *
     * <p>Called on every branch — check, uncheck, skip and unskip — because the scalars are
     * a function of the history and nothing else (KTD16). No branch adds or subtracts.
     */
    private void recordHabitDay(Habit habit, Routine routine, LocalDate date, CheckDayOutcome outcome) {
        if (outcome == null) {
            checkDayRecorder.clearDay(
                    routine.getUser(), CheckDayOwnerType.HABIT, habit.getId(),
                    habit.getCheckProgress(), date);
            return;
        }
        checkDayRecorder.record(
                routine.getUser(),
                CheckDayOwnerType.HABIT,
                habit.getId(),
                habit.getCheckProgress(),
                date,
                outcome);
    }

    /**
     * The same for a task, except one-time tasks are left alone entirely.
     *
     * <p>R4/KTD14 — a one-time task is checked once and deleted the day after, so a streak
     * over it would be a streak of one that nothing can ever extend. Writing rows for it
     * would leave orphan history behind a deleted entity for no reader.
     */
    private void recordTaskDay(Task task, Routine routine, LocalDate date, CheckDayOutcome outcome) {
        if (task.isOneTimeTask()) {
            return;
        }
        if (outcome == null) {
            checkDayRecorder.clearDay(
                    routine.getUser(), CheckDayOwnerType.TASK, task.getId(),
                    task.getCheckProgress(), date);
            return;
        }
        checkDayRecorder.record(
                routine.getUser(),
                CheckDayOwnerType.TASK,
                task.getId(),
                task.getCheckProgress(),
                date,
                outcome);
    }

    /**
     * What the day means once its check is taken away.
     *
     * <p>Judged against the routine the check came through rather than every routine the
     * user owns: the item is provably in this routine (it was just checked through it), and
     * loading the rest would cost a query on the uncheck path to change the answer only for
     * an item sitting in two routines at once.
     */
    private CheckDayOutcome absenceOutcome(CheckDayOwnerType ownerType, UUID ownerId,
                                           DiaryRoutine routine, LocalDate date) {
        // A day still running has nothing to say about having been missed, so it gets no
        // row at all and the day-close pass decides at close. See absenceOutcome's contract.
        boolean dayClosed = date.isBefore(ownerToday(routine));
        return CheckDayRecorder.absenceOutcome(
                ScheduledOnDayResolver.standingOf(ownerType, ownerId, List.of(routine), date),
                dayClosed);
    }

    /**
     * The streak the owner carries into this check, read before today's row is written.
     *
     * <p>R3/KTD6 — this is the number the XP bonus multiplies by, and it is now a real
     * streak rather than the lifetime tally {@code Habit.constance} used to hand over. No
     * floor: a habit that has never been checked, or one whose streak just broke, pays the
     * unmultiplied base.
     */
    private static int streakEntering(CheckProgress progress) {
        return progress != null ? progress.getCurrentStreak() : 0;
    }

    private RefreshUiDTO checkOrUncheckHabitGroup(HabitGroup habitGroup, LocalDate date) {
        // Check if the habit group is already checked for today
        boolean isCheckedToday = habitGroup.getHabitGroupChecks().stream()
                .peek(hc -> log.info("Evaluating check: date={}, checked={}", hc.getCheckDate(), hc.isChecked()))
                .anyMatch(habitCheck -> habitCheck.getCheckDate().equals(date) && habitCheck.isChecked());

        if (isCheckedToday) {
            // Uncheck: Remove check, subtract XP, adjust constance
            return uncheckHabitGroup(habitGroup, date);
        } else {
            // XP earn formula lives in CheckXpCalculator
            return checkHabitGroup(habitGroup, date);
        }
    }

    private RefreshUiDTO checkOrUncheckTaskGroup(TaskGroup taskGroupToCheckOrUncheck, LocalDate date) {
        // Check if the habit group is already checked for today
        boolean isCheckedToday = taskGroupToCheckOrUncheck.getTaskGroupChecks().stream()
                .anyMatch(habitCheck -> habitCheck.getCheckDate().equals(date) && habitCheck.isChecked());

        if (isCheckedToday) {
            // Uncheck: Remove check, subtract XP, adjust constance
            return uncheckTaskGroup(taskGroupToCheckOrUncheck, date);
        } else {
            // XP earn formula lives in CheckXpCalculator
            return checkTaskGroup(taskGroupToCheckOrUncheck, date);            
        }
    }

    private boolean isHabitGroupChecked(HabitGroup habitGroup, LocalDate date) {
        return habitGroup.getHabitGroupChecks().stream()
                .anyMatch(check -> check.getCheckDate().equals(date) && check.isChecked());
    }

    private boolean isTaskGroupChecked(TaskGroup taskGroup, LocalDate date) {
        return taskGroup.getTaskGroupChecks().stream()
                .anyMatch(check -> check.getCheckDate().equals(date) && check.isChecked());
    }

    private HabitGroupCheck getHabitGroupChecked(HabitGroup habitGroup, LocalDate date) {
        return habitGroup.getHabitGroupChecks().stream()
                .filter(check -> check.getCheckDate().equals(date) && check.isChecked())
                .findFirst()
                .orElse(null);
    }

    private TaskGroupCheck getTaskGroupChecked(TaskGroup taskGroup, LocalDate date) {
        return taskGroup.getTaskGroupChecks().stream()
                .filter(check -> check.getCheckDate().equals(date) && check.isChecked())
                .findFirst()
                .orElse(null);
    }

    private RefreshUiDTO skipHabitGroup(HabitGroup habitGroup, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
        HabitGroupCheck check = upsertHabitGroupCheck(habitGroup, date, false, true, 0);
        // R12 — a deliberate skip is not a failure. The row keeps the day out of the
        // MISSED column, so the streak walks straight through it, and it is not DONE, so
        // the lifetime total does not move.
        recordHabitDay(habitGroup.getHabit(), routine, date, CheckDayOutcome.SKIPPED);
        updateHabitGroupInRoutine(routine, habitGroup);
        increaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(habitGroup.getId(), check),
                routine.getUser()
        );
    }

    private RefreshUiDTO unskipHabitGroup(HabitGroup habitGroup, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
        HabitGroupCheck check = upsertHabitGroupCheck(habitGroup, date, false, false, 0);
        recordHabitDay(habitGroup.getHabit(), routine,
                date, absenceOutcome(CheckDayOwnerType.HABIT, habitGroup.getHabit().getId(), routine, date));
        updateHabitGroupInRoutine(routine, habitGroup);
        decreaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(habitGroup.getId(), check),
                routine.getUser()
        );
    }

    private RefreshUiDTO skipTaskGroup(TaskGroup taskGroup, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) taskGroup.getRoutineSection().getRoutine();
        TaskGroupCheck check = upsertTaskGroupCheck(taskGroup, date, false, true, 0);
        // R12, same as the habit side.
        recordTaskDay(taskGroup.getTask(), routine, date, CheckDayOutcome.SKIPPED);
        updateTaskGroupInRoutine(routine, taskGroup);
        increaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(taskGroup.getId(), check),
                routine.getUser()
        );
    }

    private RefreshUiDTO unskipTaskGroup(TaskGroup taskGroup, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) taskGroup.getRoutineSection().getRoutine();
        TaskGroupCheck check = upsertTaskGroupCheck(taskGroup, date, false, false, 0);
        recordTaskDay(taskGroup.getTask(), routine,
                date, absenceOutcome(CheckDayOwnerType.TASK, taskGroup.getTask().getId(), routine, date));
        updateTaskGroupInRoutine(routine, taskGroup);
        decreaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(taskGroup.getId(), check),
                routine.getUser()
        );
    }

    private RefreshUiDTO buildNoOpRefresh(UUID groupId, BaseCheck check, LocalDate date, Routine routine) {
        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(groupId, check),
                routine.getUser()
        );
    }

    private HabitGroupCheck upsertHabitGroupCheck(
            HabitGroup habitGroup,
            LocalDate date,
            boolean checked,
            boolean skipped,
            double xpGenerated
    ) {
        HabitGroupCheck check = checkIfHabitGroupIsAlreadyCheckedAndOverride(habitGroup, date);
        check.setCheckDate(date);
        check.setCheckTime(LocalTime.now());
        check.setChecked(checked);
        check.setSkipped(skipped);
        check.setXpGenerated(xpGenerated);
        check.setHabitGroup(habitGroup);
        habitGroup.getHabitGroupChecks().add(check);
        return check;
    }

    private TaskGroupCheck upsertTaskGroupCheck(
            TaskGroup taskGroup,
            LocalDate date,
            boolean checked,
            boolean skipped,
            double xpGenerated
    ) {
        TaskGroupCheck check = checkIfTaskGroupIsAlreadyCheckedAndOverrideCheck(taskGroup, date);
        check.setCheckDate(date);
        check.setCheckTime(LocalTime.now());
        check.setChecked(checked);
        check.setSkipped(skipped);
        check.setXpGenerated(xpGenerated);
        check.setTaskGroup(taskGroup);
        taskGroup.getTaskGroupChecks().add(check);
        return check;
    }

    private void updateHabitGroupInRoutine(DiaryRoutine routine, HabitGroup habitGroup) {
        for (RoutineSection section : routine.getRoutineSections()) {
            List<HabitGroup> habitGroups = section.getHabitGroups();
            for (int i = 0; i < habitGroups.size(); i++) {
                HabitGroup current = habitGroups.get(i);
                if (current.getId().equals(habitGroup.getId())) {
                    habitGroups.set(i, habitGroup);
                }
            }
        }
    }

    private void updateTaskGroupInRoutine(DiaryRoutine routine, TaskGroup taskGroup) {
        for (RoutineSection section : routine.getRoutineSections()) {
            List<TaskGroup> taskGroups = section.getTaskGroups();
            for (int i = 0; i < taskGroups.size(); i++) {
                TaskGroup current = taskGroups.get(i);
                if (current.getId().equals(taskGroup.getId())) {
                    taskGroups.set(i, taskGroup);
                }
            }
        }
    }

    private RefreshUiDTO uncheckHabitGroup(HabitGroup habitGroupToUncheck, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroupToUncheck.getRoutineSection().getRoutine();

        HabitGroupCheck existingCheck = habitGroupToUncheck.getHabitGroupChecks().stream()
                .filter(habitCheck -> habitCheck.getCheckDate().equals(date))
                .findFirst()
                .get();
        Habit habitToCheck = habitGroupToUncheck.getHabit();
        log.info("[LOG] Starting Uncheck for HabitGroupCheck => {}", existingCheck);

        // Remove xp and decrease level if needed
        habitGroupToUncheck.getHabitGroupChecks().remove(existingCheck);
        xpCalculatorService.removeXpOfUserRoutineHabitAndCategoriesAndPersist(
            routine.getUser(),
            existingCheck.getXpGenerated(),
            routine,
            habitToCheck,
            habitToCheck.getCategories()
        );
        // The day's row is rewritten to its absence outcome, never deleted — a deleted row
        // reads as unknown (R18), and "the user undid this" is knowledge. The total falls
        // back out of the recompute rather than being decremented, so it cannot go negative
        // the way the old constance counter could.
        recordHabitDay(habitToCheck, routine,
                date, absenceOutcome(CheckDayOwnerType.HABIT, habitToCheck.getId(), routine, date));

        existingCheck.setCheckDate(date);
        existingCheck.setCheckTime(LocalTime.now());
        existingCheck.setChecked(false);
        existingCheck.setSkipped(false);
        existingCheck.setXpGenerated(0);
        habitGroupToUncheck.getHabitGroupChecks().add(existingCheck);
        
        decreaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
            date, 
            habitToCheck, 
            habitToCheck.getCategories(), 
            new RefreshItemCheckedDTO(habitGroupToUncheck.getId(), existingCheck),
            routine.getUser()
        );
    }

     protected RefreshUiDTO checkTaskGroup(TaskGroup taskGroupToCheck, LocalDate date){
        DiaryRoutine routine = (DiaryRoutine) taskGroupToCheck.getRoutineSection().getRoutine();

        log.info("[LOG] Starting Check");
        Task taskChecked = taskGroupToCheck.getTask();
        TaskGroupCheck check = new TaskGroupCheck();

        check = checkIfTaskGroupIsAlreadyCheckedAndOverrideCheck(taskGroupToCheck, date);

        int dificulty = taskChecked.getDificulty() != null ? taskChecked.getDificulty() : 1;
        int importance = taskChecked.getImportance() != null ? taskChecked.getImportance() : 1;

        //Set check object
        check.setCheckDate(date);
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setSkipped(false);
        check.setXpGenerated(0);
        check.setTaskGroup(taskGroupToCheck);

        //Update categories
        if(taskChecked.getCategories() != null && taskChecked.getCategories().size() > 0){
            // R3 — a recurring task carries its own streak now, so it earns the same bonus a
            // habit does. A one-time task never builds one, so its progress stays at zero and
            // it pays the unmultiplied base.
            double newXp = CheckXpCalculator.calculate(dificulty, importance,
                    taskChecked.isOneTimeTask() ? 0 : streakEntering(taskChecked.getCheckProgress()));
            check.setXpGenerated(newXp);
            xpCalculatorService.addXpToUserRoutineAndCategoriesAndPersist(
                routine.getUser(),
                newXp,
                routine,
                taskChecked.getCategories()
            );
        }

        recordTaskDay(taskChecked, routine, date, CheckDayOutcome.DONE);

        //Mark to delete if one time task — dated in the owner's zone, same as the check row,
        //so cleanup ("delete once that day has passed") agrees with what the user saw.
        if(taskChecked.isOneTimeTask()){
            taskChecked.setMarkedToDelete(date);
        }

        //Update entities
        taskGroupToCheck.getTaskGroupChecks().add(check);
        updateTaskGroupInRoutine(routine, taskGroupToCheck);

        increaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date, 
                null, 
                taskChecked.getCategories(),
                new RefreshItemCheckedDTO(
                    taskGroupToCheck.getId(),
                    check
                ),
                routine.getUser()
            );
    }

    private RefreshUiDTO checkHabitGroup(HabitGroup habitGroupToCheckOrUncheck, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroupToCheckOrUncheck.getRoutineSection().getRoutine();

        log.info("[LOG] Starting Check");
        Habit habitChecked = habitGroupToCheckOrUncheck.getHabit();
        HabitGroupCheck check = null;

        check = checkIfHabitGroupIsAlreadyCheckedAndOverride(habitGroupToCheckOrUncheck, date);

        // R3 — the bonus multiplies the streak entering today, read before today's row is
        // written. Recording first would fold today's own check into its own multiplier.
        double newXp = CheckXpCalculator.calculate(
                habitChecked.getDificulty(), habitChecked.getImportance(),
                streakEntering(habitChecked.getCheckProgress()));
        xpCalculatorService.addXpToUserRoutineHabitAndCategoriesAndPersist(
            routine.getUser(),
            newXp,
            routine,
            habitChecked,
            habitChecked.getCategories()
        );
        recordHabitDay(habitChecked, routine, date, CheckDayOutcome.DONE);

        // Set check object
        check.setCheckDate(date);
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setSkipped(false);
        check.setXpGenerated(newXp);
        check.setHabitGroup(habitGroupToCheckOrUncheck);

        habitGroupToCheckOrUncheck.getHabitGroupChecks().add(check);
        updateHabitGroupInRoutine(routine, habitGroupToCheckOrUncheck);

        increaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
            date,
            habitChecked, 
            habitChecked.getCategories(),
            new RefreshItemCheckedDTO(
                habitGroupToCheckOrUncheck.getId(),
                check
            ),
            routine.getUser()
        );
    }

    private RefreshUiDTO uncheckTaskGroup(TaskGroup taskGroupUnchecked, LocalDate date){
        DiaryRoutine routine = (DiaryRoutine) taskGroupUnchecked.getRoutineSection().getRoutine();
        
        log.info("[LOG] Starting unchecking");
        Task taskChecked = taskGroupUnchecked.getTask();

        TaskGroupCheck existingCheck = taskGroupUnchecked.getTaskGroupChecks().stream()
            .filter(taskCheck -> taskCheck.getCheckDate().equals(date))
            .findFirst()
            .get();

        //Clean the check and remove the xp generated in the categories
        taskGroupUnchecked.getTaskGroupChecks().remove(existingCheck);
        if(taskChecked.getCategories() != null && taskChecked.getCategories().size() > 0){
            xpCalculatorService.removeXpOfUserRoutineAndCategoriesAndPersist(
                routine.getUser(),
                existingCheck.getXpGenerated(),
                routine,
                taskChecked.getCategories()
            );
        }

        recordTaskDay(taskChecked, routine,
                date, absenceOutcome(CheckDayOwnerType.TASK, taskChecked.getId(), routine, date));

        //Remove marked to delete if has
        if(taskChecked.isOneTimeTask()){
            taskChecked.setMarkedToDelete(null);
        }

        existingCheck.setCheckDate(date);
        existingCheck.setCheckTime(LocalTime.now());
        existingCheck.setChecked(false);
        existingCheck.setSkipped(false);
        existingCheck.setXpGenerated(0);
        taskGroupUnchecked.getTaskGroupChecks().add(existingCheck);
        
        decreaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
            date, 
            null, 
            taskChecked.getCategories(),
            new RefreshItemCheckedDTO(
                taskGroupUnchecked.getId(),
                existingCheck
            ),
            routine.getUser()
        );
    }

    private HabitGroupCheck checkIfHabitGroupIsAlreadyCheckedAndOverride(HabitGroup habitGroup, LocalDate date) {
        Optional<HabitGroupCheck> existingCheck = habitGroup.getHabitGroupChecks().stream()
                .filter(tc -> tc.getCheckDate().equals(date))
                .findFirst();

        if (existingCheck.isPresent()) {
            log.info("[LOG] HabitGroup already have check for today, overriting this one => {}", habitGroup);
            habitGroup.getHabitGroupChecks().remove(existingCheck.get());
            return existingCheck.get();
        }
        return new HabitGroupCheck();
    }

    private TaskGroupCheck checkIfTaskGroupIsAlreadyCheckedAndOverrideCheck(TaskGroup taskGroup, LocalDate date){
        Optional<TaskGroupCheck> existingCheck = taskGroup.getTaskGroupChecks().stream()
            .filter(tc -> tc.getCheckDate().equals(date))
            .findFirst();

        if (existingCheck.isPresent()) {
            log.info("[LOG] TaskGroup already have check for today, overriting this one => {}", taskGroup);
            taskGroup.getTaskGroupChecks().remove(existingCheck.get());
            return existingCheck.get();
        }
        return new TaskGroupCheck();
    }

    private void decreaseUserConstanceIfNeeded(DiaryRoutine routine, LocalDate date) {
        // Identity travels with the data (the routine's owner), not a ThreadLocal:
        // agent tools run on a boundedElastic thread with no SecurityContext.
        User user = routine.getUser();
        user.setCompletedDays(user.getCompletedDays() == null ? new HashSet<>() : user.getCompletedDays());

        log.info("[DEBUG] Checking date => {} in user dates {}", date, user.getCompletedDays());
        if(!user.getCompletedDays().contains(date)) return ; //No constance to decrease today
        log.info("[DEBUG] Date found");

        if(user != null && user.getConstanceConfiguration() != null){
            switch (user.getConstanceConfiguration()) {
                case COMPLETE:
                    if(!isAllHabitGroupsCompleted(routine, date) || !isAllTaskGroupsCompleted(routine, date)){
                        log.info("[SERVICE] Unmarking constance for user {}, in constance config COMPLETE", user.getName());
                        userService.unmarkDayComplete(user, date);
                    }
                    break;
                default: //ANY
                    if(!isAnyHabitGroupCompleted(routine, date) && !isAnyTaskGroupCompleted(routine, date)){
                        log.info("[SERVICE] Decreasing constance for user {}, in constance config ANY", user.getName());
                        userService.unmarkDayComplete(user, date);
                    }
                    break;
            }
        }
    }

    private void increaseUserConstanceIfNeeded(DiaryRoutine routine, LocalDate date) {
        // Identity travels with the data (the routine's owner), not a ThreadLocal:
        // agent tools run on a boundedElastic thread with no SecurityContext.
        User user = routine.getUser();

        user.setCompletedDays(user.getCompletedDays() == null ? new HashSet<>() : user.getCompletedDays());
        if(user.getCompletedDays().contains(date) == true) return ; //Already increased today

        if(user.getConstanceConfiguration() != null){
            switch (user.getConstanceConfiguration()) {
                case COMPLETE:
                    if(isAllHabitGroupsCompleted(routine, date) && isAllTaskGroupsCompleted(routine, date)){
                        log.info("[SERVICE] Increasing constance for user {}, in constance config COMPLETE", user.getName());
                        userService.markDayCompleted(user, date);
                    }
                    break;
                default: //ANY
                    if(isAnyHabitGroupCompleted(routine, date) || isAnyTaskGroupCompleted(routine, date)){
                        log.info("[SERVICE] Increasing constance for user {}, in constance config ANY", user.getName());
                        userService.markDayCompleted(user, date);
                    }
                    break;
            }
        }
    }

    private boolean isAnyHabitGroupCompleted(DiaryRoutine routine, LocalDate date) {
        return routine.getRoutineSections().stream()
            .anyMatch(section -> section.getHabitGroups().stream()
                .anyMatch(group -> isHabitGroupCompleted(group, date))
            );
    }

    private boolean isAllHabitGroupsCompleted(DiaryRoutine routine, LocalDate date) {
        return routine.getRoutineSections().stream()
            .allMatch(section -> areAllHabitGroupsCompleted(section, date));
    }

    private boolean areAllHabitGroupsCompleted(RoutineSection section, LocalDate date) {
        return section.getHabitGroups().stream()
            .allMatch(group -> isHabitGroupCompletedOrSkipped(group, date));
    }

    private boolean isHabitGroupCompleted(HabitGroup group, LocalDate date) {
        return group.getHabitGroupChecks().stream()
            .anyMatch(check ->
                check.getCheckDate().equals(date) && check.isChecked()
            );
    }

    private boolean isHabitGroupCompletedOrSkipped(HabitGroup group, LocalDate date) {
        return group.getHabitGroupChecks().stream()
            .anyMatch(check ->
                check.getCheckDate().equals(date)
                    && (check.isChecked() || Boolean.TRUE.equals(check.getSkipped()))
            );
    }

    private boolean isAnyTaskGroupCompleted(DiaryRoutine routine, LocalDate date) {
        return routine.getRoutineSections().stream()
            .anyMatch(section -> section.getTaskGroups().stream()
                .anyMatch(group -> isTaskGroupCompleted(group, date))
            );

    }

    private boolean isAllTaskGroupsCompleted(DiaryRoutine routine, LocalDate date) {
        return routine.getRoutineSections().stream()
            .allMatch(section -> areAllTaskGroupsCompleted(section, date));
    }

    private boolean areAllTaskGroupsCompleted(RoutineSection section, LocalDate date) {
        return section.getTaskGroups().stream()
            .allMatch(group -> isTaskGroupCompletedOrSkipped(group, date));
    }

    private boolean isTaskGroupCompleted(TaskGroup group, LocalDate date) {
        return group.getTaskGroupChecks().stream()
            .anyMatch(check ->
                check.getCheckDate().equals(date) && check.isChecked()
            );
    }

    private boolean isTaskGroupCompletedOrSkipped(TaskGroup group, LocalDate date) {
        return group.getTaskGroupChecks().stream()
            .anyMatch(check ->
                check.getCheckDate().equals(date)
                    && (check.isChecked() || Boolean.TRUE.equals(check.getSkipped()))
            );
    }

}
