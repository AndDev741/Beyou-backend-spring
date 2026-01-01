package beyou.beyouapp.backend.domain.routine.checks;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class CheckItemService {

    private final HabitService habitService;
    private final TaskService taskService;
    private final ItemGroupService itemGroupService;
    private final XpByLevelRepository xpByLevelRepository;

    @Transactional
    public DiaryRoutine checkOrUncheckItemGroup(CheckGroupRequestDTO checkGroupDTO) {
        LocalDate date = checkGroupDTO.date() != null ? checkGroupDTO.date() : LocalDate.now();
        if(checkGroupDTO.habitGroupDTO() != null){
            HabitGroup habitGroup = itemGroupService.findHabitGroupByDTO(checkGroupDTO.habitGroupDTO().habitGroupId());
            return checkOrUncheckHabitGroup(habitGroup, date);
        }else if(checkGroupDTO.taskGroupDTO() != null){
            TaskGroup taskGroup = itemGroupService.findTaskGroupByDTO(checkGroupDTO.taskGroupDTO().taskGroupId());
            return checkOrUncheckTaskGroup(taskGroup, date);
        }else{
            throw new RuntimeException("No Item group found in the request");
        }
    }

    private DiaryRoutine checkOrUncheckHabitGroup(HabitGroup habitGroup, LocalDate date) {
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

    private DiaryRoutine checkOrUncheckTaskGroup(TaskGroup taskGroupToCheckOrUncheck, LocalDate date) {
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

    private DiaryRoutine uncheckHabitGroup(HabitGroup habitGroupToUncheck, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroupToUncheck.getRoutineSection().getRoutine();
        HabitGroupCheck existingCheck = habitGroupToUncheck.getHabitGroupChecks().stream()
                .filter(habitCheck -> habitCheck.getCheckDate().equals(date))
                .findFirst()
                .get();
        Habit habitToCheck = habitGroupToUncheck.getHabit();
        log.info("[LOG] Starting Uncheck for HabitGroupCheck => {}", existingCheck);

        // Remove xp, decrease level if needed and remove constance
        habitGroupToUncheck.getHabitGroupChecks().remove(existingCheck);
        habitToCheck.getXpProgress().removeXp(
                existingCheck.getXpGenerated(),
                level -> xpByLevelRepository.findByLevel(level));
        habitToCheck.setConstance(habitToCheck.getConstance() - 1);

        habitToCheck.getCategories().forEach(c -> c.getXpProgress().removeXp(
                existingCheck.getXpGenerated(),
                level -> xpByLevelRepository.findByLevel(level)));
        habitService.editEntity(habitToCheck);

        existingCheck.setCheckDate(date);
        existingCheck.setCheckTime(LocalTime.now());
        existingCheck.setChecked(false);
        existingCheck.setXpGenerated(0);
        habitGroupToUncheck.getHabitGroupChecks().add(existingCheck);
        return routine;
    }

     protected DiaryRoutine checkTaskGroup(TaskGroup taskGroupToCheck, LocalDate date){
        DiaryRoutine routine = (DiaryRoutine) taskGroupToCheck.getRoutineSection().getRoutine();
        log.info("[LOG] Starting Check");
        Task taskChecked = taskGroupToCheck.getTask();
        TaskGroupCheck check = new TaskGroupCheck();

        check = checkIfTaskGroupIsAlreadyCheckedAndOverrideCheck(taskGroupToCheck, date);

        int dificulty = taskChecked.getDificulty() != null ? taskChecked.getDificulty() : 1;
        int importance = taskChecked.getImportance() != null ? taskChecked.getImportance() : 1;
        Double newXp = (double) (10 * dificulty * importance);

        //Set check object
        check.setCheckDate(date);
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setXpGenerated(0);
        check.setTaskGroup(taskGroupToCheck);

        //Update categories
        if(taskChecked.getCategories() != null && taskChecked.getCategories().size() > 0){
            log.info("Task have category");
            check.setXpGenerated(newXp);
            taskChecked.getCategories().forEach(c -> 
                c.getXpProgress().addXp(
                    newXp,
                    level -> xpByLevelRepository.findByLevel(level))
            );
        }
        
        //Mark to delete if one time task
        if(taskChecked.isOneTimeTask()){
            taskChecked.setMarkedToDelete(LocalDate.now());
        }

        //Update entities
        taskGroupToCheck.getTaskGroupChecks().add(check);
        taskService.editTask(taskChecked);
        for (RoutineSection section : routine.getRoutineSections()) {
            List<TaskGroup> taskGroups = section.getTaskGroups();
            for (int i = 0; i < taskGroups.size(); i++) {
                TaskGroup current = taskGroups.get(i);
                if (current.getId().equals(taskGroupToCheck.getId())) {
                    taskGroups.set(i, taskGroupToCheck);
                }
            }
        }
        return routine;
    }

    private DiaryRoutine checkHabitGroup(HabitGroup habitGroupToCheckOrUncheck, LocalDate date) {
        DiaryRoutine routine = (DiaryRoutine) habitGroupToCheckOrUncheck.getRoutineSection().getRoutine();
        log.info("[LOG] Starting Check");
        Habit habitChecked = habitGroupToCheckOrUncheck.getHabit();
        HabitGroupCheck check = null;

        check = checkIfHabitGroupIsAlreadyCheckedAndOverride(habitGroupToCheckOrUncheck, date);

        Double newXp = (double) (10 * habitChecked.getDificulty() * habitChecked.getImportance());
        habitChecked.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level));
        // 1 more for the constance
        habitChecked.setConstance(habitChecked.getConstance() + 1);

        // Update categories xp
        List<Category> categories = habitChecked.getCategories();
        categories.forEach(c -> c.getXpProgress().addXp(
                newXp,
                level -> xpByLevelRepository.findByLevel(level)));

        // Set check object
        check.setCheckDate(date);
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setXpGenerated(newXp);
        check.setHabitGroup(habitGroupToCheckOrUncheck);

        habitGroupToCheckOrUncheck.getHabitGroupChecks().add(check);

        // Update entities
        habitService.editEntity(habitChecked);
        for (RoutineSection section : routine.getRoutineSections()) {
            List<HabitGroup> habitGroups = section.getHabitGroups();
            for (int i = 0; i < habitGroups.size(); i++) {
                HabitGroup current = habitGroups.get(i);
                if (current.getId().equals(habitGroupToCheckOrUncheck.getId())) {
                    habitGroups.set(i, habitGroupToCheckOrUncheck);
                }
            }
        }
        return routine;
    }

    private DiaryRoutine uncheckTaskGroup(TaskGroup taskGroupUnchecked, LocalDate date){
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
            taskChecked.getCategories().forEach(c -> 
                c.getXpProgress().removeXp(
                    existingCheck.getXpGenerated(),
                    level -> xpByLevelRepository.findByLevel(level))
            );
        }

        //Remove marked to delete if has
        if(taskChecked.isOneTimeTask()){
            taskChecked.setMarkedToDelete(null);
        }

        taskService.editTask(taskChecked);

        existingCheck.setCheckDate(date);
        existingCheck.setCheckTime(LocalTime.now());
        existingCheck.setChecked(false);
        existingCheck.setXpGenerated(0);
        taskGroupUnchecked.getTaskGroupChecks().add(existingCheck);
        return routine;
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
}
