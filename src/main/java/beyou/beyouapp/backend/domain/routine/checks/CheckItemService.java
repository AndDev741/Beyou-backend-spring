package beyou.beyouapp.backend.domain.routine.checks;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshItemCheckedDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
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
    private final AuthenticatedUser authenticatedUser;
    private final UserService userService;
    private final RefreshUiDtoBuilder refreshUiDtoBuilder;

    @Transactional
    public RefreshUiDTO checkOrUncheckItemGroup(CheckGroupRequestDTO checkGroupDTO) {
        LocalDate date = checkGroupDTO.date() != null ? checkGroupDTO.date() : LocalDate.now();
        if(checkGroupDTO.habitGroupDTO() != null){
            HabitGroup habitGroup = itemGroupService.findHabitGroupByDTO(checkGroupDTO.routineId(), checkGroupDTO.habitGroupDTO().habitGroupId());
            return checkOrUncheckHabitGroup(habitGroup, date);
        }else if(checkGroupDTO.taskGroupDTO() != null){
            TaskGroup taskGroup = itemGroupService.findTaskGroupByDTO(checkGroupDTO.routineId(), checkGroupDTO.taskGroupDTO().taskGroupId());
            return checkOrUncheckTaskGroup(taskGroup, date);
        }else{
            throw new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED, "No Item group found in the request");
        }
    }

    @Transactional
    public RefreshUiDTO skipOrUnskipItemGroup(SkipGroupRequestDTO skipGroupDTO) {
        LocalDate date = skipGroupDTO.date() != null ? skipGroupDTO.date() : LocalDate.now();
        if(skipGroupDTO.habitGroupDTO() != null){
            HabitGroup habitGroup = itemGroupService.findHabitGroupByDTO(skipGroupDTO.routineId(), skipGroupDTO.habitGroupDTO().habitGroupId());
            if (isHabitGroupChecked(habitGroup, date)) {
                return buildNoOpRefresh(habitGroup.getId(), getHabitGroupChecked(habitGroup, date), date);
            }
            return skipGroupDTO.skip()
                ? skipHabitGroup(habitGroup, date)
                : unskipHabitGroup(habitGroup, date);
        }else if(skipGroupDTO.taskGroupDTO() != null){
            TaskGroup taskGroup = itemGroupService.findTaskGroupByDTO(skipGroupDTO.routineId(), skipGroupDTO.taskGroupDTO().taskGroupId());
            if (isTaskGroupChecked(taskGroup, date)) {
                return buildNoOpRefresh(taskGroup.getId(), getTaskGroupChecked(taskGroup, date), date);
            }
            return skipGroupDTO.skip()
                ? skipTaskGroup(taskGroup, date)
                : unskipTaskGroup(taskGroup, date);
        }else{
            throw new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED, "No Item group found in the request");
        }
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
            // Calculate the exp (Think in a good algorithm later on)
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
            // Calculate the exp (Think in a good algorithm later on)
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
        updateHabitGroupInRoutine(routine, habitGroup);
        increaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(habitGroup.getId(), check)
        );
    }

    private RefreshUiDTO unskipHabitGroup(HabitGroup habitGroup, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroup.getRoutineSection().getRoutine();
        HabitGroupCheck check = upsertHabitGroupCheck(habitGroup, date, false, false, 0);
        updateHabitGroupInRoutine(routine, habitGroup);
        decreaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(habitGroup.getId(), check)
        );
    }

    private RefreshUiDTO skipTaskGroup(TaskGroup taskGroup, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) taskGroup.getRoutineSection().getRoutine();
        TaskGroupCheck check = upsertTaskGroupCheck(taskGroup, date, false, true, 0);
        updateTaskGroupInRoutine(routine, taskGroup);
        increaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(taskGroup.getId(), check)
        );
    }

    private RefreshUiDTO unskipTaskGroup(TaskGroup taskGroup, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) taskGroup.getRoutineSection().getRoutine();
        TaskGroupCheck check = upsertTaskGroupCheck(taskGroup, date, false, false, 0);
        updateTaskGroupInRoutine(routine, taskGroup);
        decreaseUserConstanceIfNeeded(routine, date);

        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(taskGroup.getId(), check)
        );
    }

    private RefreshUiDTO buildNoOpRefresh(UUID groupId, BaseCheck check, LocalDate date) {
        return refreshUiDtoBuilder.buildRefreshUiDto(
                date,
                null,
                null,
                new RefreshItemCheckedDTO(groupId, check)
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

        // Remove xp, decrease level if needed and remove constance
        habitGroupToUncheck.getHabitGroupChecks().remove(existingCheck);
        xpCalculatorService.removeXpOfUserRoutineHabitAndCategoriesAndPersist(
            existingCheck.getXpGenerated(),
            routine, 
            habitToCheck, 
            habitToCheck.getCategories()
        );
        habitToCheck.setConstance(habitToCheck.getConstance() - 1);

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
            new RefreshItemCheckedDTO(habitGroupToUncheck.getId(), existingCheck)
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
            Double newXp = (double) (10 * dificulty * importance);
            check.setXpGenerated(newXp);
            xpCalculatorService.addXpToUserRoutineAndCategoriesAndPersist(
                newXp,
                routine, 
                taskChecked.getCategories()
            );
        }
        
        //Mark to delete if one time task
        if(taskChecked.isOneTimeTask()){
            taskChecked.setMarkedToDelete(LocalDate.now());
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
                )
            );
    }

    private RefreshUiDTO checkHabitGroup(HabitGroup habitGroupToCheckOrUncheck, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroupToCheckOrUncheck.getRoutineSection().getRoutine();

        log.info("[LOG] Starting Check");
        Habit habitChecked = habitGroupToCheckOrUncheck.getHabit();
        HabitGroupCheck check = null;

        check = checkIfHabitGroupIsAlreadyCheckedAndOverride(habitGroupToCheckOrUncheck, date);

        Double newXp = (double) (10 * habitChecked.getDificulty() * habitChecked.getImportance());
        xpCalculatorService.addXpToUserRoutineHabitAndCategoriesAndPersist(
            newXp, 
            routine, 
            habitChecked, 
            habitChecked.getCategories()
        );
        habitChecked.setConstance(habitChecked.getConstance() + 1);

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
            )
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
                existingCheck.getXpGenerated(),
                routine, 
                taskChecked.getCategories()
            );
        }

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
            )
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
        User user = authenticatedUser.getAuthenticatedUser();
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
        User user = authenticatedUser.getAuthenticatedUser();

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
