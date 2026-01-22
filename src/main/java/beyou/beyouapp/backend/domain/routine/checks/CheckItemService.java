package beyou.beyouapp.backend.domain.routine.checks;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.common.DTO.RefreshItemCheckedDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshObjectDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
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
            throw new RuntimeException("No Item group found in the request");
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
        existingCheck.setXpGenerated(0);
        habitGroupToUncheck.getHabitGroupChecks().add(existingCheck);
        
        decreaseUserConstanceIfNeeded(routine, date);

        return buildRefreshDto(
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
        for (RoutineSection section : routine.getRoutineSections()) {
            List<TaskGroup> taskGroups = section.getTaskGroups();
            for (int i = 0; i < taskGroups.size(); i++) {
                TaskGroup current = taskGroups.get(i);
                if (current.getId().equals(taskGroupToCheck.getId())) {
                    taskGroups.set(i, taskGroupToCheck);
                }
            }
        }

        increaseUserConstanceIfNeeded(routine, date);

        return buildRefreshDto(
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
        check.setXpGenerated(newXp);
        check.setHabitGroup(habitGroupToCheckOrUncheck);

        habitGroupToCheckOrUncheck.getHabitGroupChecks().add(check);

        for (RoutineSection section : routine.getRoutineSections()) {
            List<HabitGroup> habitGroups = section.getHabitGroups();
            for (int i = 0; i < habitGroups.size(); i++) {
                HabitGroup current = habitGroups.get(i);
                if (current.getId().equals(habitGroupToCheckOrUncheck.getId())) {
                    habitGroups.set(i, habitGroupToCheckOrUncheck);
                }
            }
        }

        increaseUserConstanceIfNeeded(routine, date);

        return buildRefreshDto(
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
        existingCheck.setXpGenerated(0);
        taskGroupUnchecked.getTaskGroupChecks().add(existingCheck);
        
        decreaseUserConstanceIfNeeded(routine, date);

        return buildRefreshDto(
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
            .allMatch(group -> isHabitGroupCompleted(group, date));
    }

    private boolean isHabitGroupCompleted(HabitGroup group, LocalDate date) {
        return group.getHabitGroupChecks().stream()
            .anyMatch(check ->
                check.getCheckDate().equals(date) && check.isChecked()
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
            .allMatch(group -> isTaskGroupCompleted(group, date));
    }

    private boolean isTaskGroupCompleted(TaskGroup group, LocalDate date) {
        return group.getTaskGroupChecks().stream()
            .anyMatch(check ->
                check.getCheckDate().equals(date) && check.isChecked()
            );
    }

    private RefreshUiDTO buildRefreshDto(
        LocalDate date, 
        Habit habitToRefresh, 
        List<Category> categoriesToRefresh, 
        RefreshItemCheckedDTO refreshItemCheckedDTO
    ) {

        RefreshObjectDTO habitToRefreshDto = null;
        if(habitToRefresh != null) {
            habitToRefreshDto = new RefreshObjectDTO(
                habitToRefresh.getId(),
                habitToRefresh.getXpProgress().getXp(),
                habitToRefresh.getXpProgress().getLevel(),
                habitToRefresh.getXpProgress().getActualLevelXp(),
                habitToRefresh.getXpProgress().getNextLevelXp()
            );
        }

        List<RefreshObjectDTO> categoriesToRefreshDto = new ArrayList<RefreshObjectDTO>();
        if(categoriesToRefresh != null){
            categoriesToRefresh.forEach(c -> {
                categoriesToRefreshDto.add(
                    new RefreshObjectDTO(
                        c.getId(),
                        c.getXpProgress().getXp(),
                        c.getXpProgress().getLevel(),
                        c.getXpProgress().getActualLevelXp(),
                        c.getXpProgress().getNextLevelXp()
                    )
                );
            });
        }

        User userInContext = authenticatedUser.getAuthenticatedUser();
        User freshUser = userService.findUserById(userInContext.getId());
        RefreshUserDTO refreshUserDTO = new RefreshUserDTO(
            freshUser.getCurrentConstance(date),
            freshUser.getCompletedDays().contains(date),
            freshUser.getMaxConstance(),
            freshUser.getXpProgress().getXp(),
            freshUser.getXpProgress().getLevel(),
            freshUser.getXpProgress().getActualLevelXp(),
            freshUser.getXpProgress().getNextLevelXp()
        );

        return new RefreshUiDTO(
            refreshUserDTO,
            categoriesToRefreshDto,
            habitToRefreshDto,
            refreshItemCheckedDTO
        );
    }
}
