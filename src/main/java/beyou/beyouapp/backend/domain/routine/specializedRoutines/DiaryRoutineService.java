package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO.RoutineSectionResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO.RoutineSectionResponseDTO.TaskGroupResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO.RoutineSectionResponseDTO.HabitGroupResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DiaryRoutineService {

    private final DiaryRoutineRepository diaryRoutineRepository;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private final TaskService taskService;
    private final HabitService habitService;
    private final XpByLevelRepository xpByLevelRepository;
    private final CategoryRepository categoryRepository;

    @Transactional(readOnly = true)
    public DiaryRoutineResponseDTO getDiaryRoutineById(UUID id, UUID userId) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));
        if(!diaryRoutine.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }
        return mapToResponseDTO(diaryRoutine);
    }

    @Transactional(readOnly = true)
    public DiaryRoutine getDiaryRoutineByScheduleId(UUID scheduleId, UUID userId) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        if(!diaryRoutine.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }
        return diaryRoutine;
    }

    @Transactional(readOnly = true)
    public DiaryRoutine getDiaryRoutineModelById(UUID id, UUID userId) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));
        if(!diaryRoutine.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }
        return diaryRoutine;
    }

    @Transactional(readOnly = true)
    public List<DiaryRoutineResponseDTO> getAllDiaryRoutines(UUID userId) {
        return diaryRoutineRepository.findAllByUserId(userId).stream()
                .map(this::mapToResponseDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<DiaryRoutine> getAllDiaryRoutinesModels(UUID userId) {
        return diaryRoutineRepository.findAllByUserId(userId).stream()
                .collect(Collectors.toList());
    }

    @Transactional
    public DiaryRoutineResponseDTO createDiaryRoutine(DiaryRoutineRequestDTO dto, User user) {
        validateRequestDTO(dto);
        DiaryRoutine diaryRoutine = mapToEntity(dto);
        diaryRoutine.setUser(user);
        DiaryRoutine saved = diaryRoutineRepository.save(diaryRoutine);
        return mapToResponseDTO(saved);
    }

    @Transactional
    public DiaryRoutineResponseDTO updateDiaryRoutine(UUID id, DiaryRoutineRequestDTO dto, UUID userId) {
        validateRequestDTO(dto);
        DiaryRoutine existing = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        if(!existing.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }

        existing.setName(dto.name());
        existing.setIconId(dto.iconId());
        existing.getRoutineSections().clear();
        List<RoutineSection> newSections = mapToRoutineSections(dto.routineSections(), existing);
        existing.getRoutineSections().addAll(newSections);

        DiaryRoutine updated = diaryRoutineRepository.save(existing);
        return mapToResponseDTO(updated);
    }

    @Transactional
    public void updateSchedule(DiaryRoutine routine){
        diaryRoutineRepository.save(routine);
    }

    @Transactional
    public void deleteDiaryRoutine(UUID id, UUID userId) {
        Optional<DiaryRoutine> diaryRoutineToDelete = diaryRoutineRepository.findById(id);

        if (diaryRoutineToDelete.isEmpty() || !diaryRoutineToDelete.get().getUser().getId().equals(userId)) {
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }

        diaryRoutineRepository.deleteById(id);
    }

    @Transactional
    public DiaryRoutineResponseDTO getTodayRoutineScheduled(UUID userId){
        List<DiaryRoutine> diaryRoutines = diaryRoutineRepository.findAllByUserId(userId);

        DiaryRoutine todaysRoutine = null;
        String dayOfWeek = LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        log.info("Day: {} ", dayOfWeek);
        for (DiaryRoutine diaryRoutine : diaryRoutines) {
            if(diaryRoutine.getSchedule().getDays().contains(dayOfWeek)){
                log.info("Routine {} are scheduled for today", diaryRoutine.getName());
                todaysRoutine = diaryRoutine;
            }
        }

        if(todaysRoutine == null){
            throw new DiaryRoutineNotFoundException("No Routine are scheduled for today");
        }else{
            return mapToResponseDTO(todaysRoutine);
        }

    }

    private void validateRequestDTO(DiaryRoutineRequestDTO dto) {
        if (dto.name() == null || dto.name().trim().isEmpty()) {
            throw new IllegalArgumentException("DiaryRoutine name cannot be null or empty");
        }
        if (dto.routineSections() == null) {
            throw new IllegalArgumentException("Routine sections cannot be null");
        }
        for (var section : dto.routineSections()) {
            if (section.name() == null || section.name().trim().isEmpty()) {
                throw new IllegalArgumentException("Routine section name cannot be null or empty");
            }
            if (section.startTime() != null && section.endTime() != null
                    && section.endTime().isBefore(section.startTime())) {
                throw new IllegalArgumentException(
                        "End time must be after start time for routine section: " + section.name());
            }
        }
    }

    private DiaryRoutine mapToEntity(DiaryRoutineRequestDTO dto) {
        DiaryRoutine diaryRoutine = new DiaryRoutine();
        diaryRoutine.setName(dto.name());
        diaryRoutine.setIconId(dto.iconId());
        List<RoutineSection> sections = mapToRoutineSections(dto.routineSections(), diaryRoutine);
        diaryRoutine.setRoutineSections(sections);
        return diaryRoutine;
    }

    private List<RoutineSection> mapToRoutineSections(List<RoutineSectionRequestDTO> dtos, DiaryRoutine diaryRoutine) {
        return dtos.stream().map(dto -> {
            RoutineSection section = new RoutineSection();
            log.info("ID OF SECTION ITEM =» {}", dto.id());
            if(dto.id() != null){
                section.setId(dto.id());
            }
            section.setName(dto.name());
            section.setIconId(dto.iconId());
            section.setStartTime(dto.startTime());
            section.setEndTime(dto.endTime());
            section.setRoutine(diaryRoutine);

            List<TaskGroup> taskGroups = dto.taskGroup() != null ? dto.taskGroup().stream().map(taskDto -> {
                TaskGroup taskGroup = new TaskGroup();
                log.info(null == taskDto.taskId() ? "Task ID is null" : "Task ID: {}", taskDto.taskId());
                Task task = taskService.getTask(taskDto.taskId());

                if(taskDto.id() != null){
                    taskGroup.setId(taskDto.id());
                }
                
                if (taskDto.taskGroupCheck() != null) {
                    List<TaskGroupCheck> checks = taskGroup.getTaskGroupChecks(); 
                    checks.addAll(taskDto.taskGroupCheck());
                    taskGroup.setTaskGroupChecks(checks);
                }                
                taskGroup.setTask(task);
                taskGroup.setStartTime(taskDto.startTime());
                taskGroup.setRoutineSection(section);

                return taskGroup;
            }).collect(Collectors.toList()) : List.of();

            section.setTaskGroups(taskGroups);

            List<HabitGroup> habitGroups = dto.habitGroup() != null ? dto.habitGroup().stream().map(habitDto -> {
                HabitGroup habitGroup = new HabitGroup();
                Habit habit = habitService.getHabit(habitDto.habitId());

                for (RoutineSection routineSection : diaryRoutine.getRoutineSections()) {
                    List<HabitGroup> actualHabitGroups = routineSection.getHabitGroups();
                    for (int i = 0; i < actualHabitGroups.size(); i++) {
                        actualHabitGroups.get(i).getHabitGroupChecks().clear();
                    }
                }

                if(habitDto.id() != null){
                    habitGroup.setId(habitDto.id());
                }
                if (habitDto.habitGroupCheck() != null) {
                    List<HabitGroupCheck> checks = habitGroup.getHabitGroupChecks();
                    checks.addAll(habitDto.habitGroupCheck());
                    habitGroup.setHabitGroupChecks(checks);
                }
                habitGroup.setHabit(habit);
                habitGroup.setStartTime(habitDto.startTime());
                habitGroup.setRoutineSection(section);

                return habitGroup;
            }).collect(Collectors.toList()) : List.of();
            section.setHabitGroups(habitGroups);

            return section;
        }).collect(Collectors.toList());
    }

    private DiaryRoutineResponseDTO mapToResponseDTO(DiaryRoutine entity) {
        List<RoutineSectionResponseDTO> sectionDTOs = entity.getRoutineSections().stream().map(section -> {
            List<TaskGroupResponseDTO> taskGroupDTOs = section.getTaskGroups().stream()
                    .map(taskGroup -> new TaskGroupResponseDTO(
                            taskGroup.getId(),
                            taskGroup.getTask().getId(),
                            taskGroup.getStartTime() != null ? taskGroup.getStartTime().format(TIME_FORMATTER) : null,
                            taskGroup.getTaskGroupChecks()
                            ))
                    .collect(Collectors.toList());

            List<HabitGroupResponseDTO> habitGroupDTOs = section.getHabitGroups().stream()
                    .map(habitGroup -> new HabitGroupResponseDTO(
                            habitGroup.getId(),
                            habitGroup.getHabit().getId(),
                            habitGroup.getStartTime() != null ? habitGroup.getStartTime().format(TIME_FORMATTER)
                                    : null,
                            habitGroup.getHabitGroupChecks()
                                    ))
                    .collect(Collectors.toList());

            return new RoutineSectionResponseDTO(
                    section.getId(),
                    section.getName(),
                    section.getIconId(),
                    section.getStartTime() != null ? section.getStartTime().format(TIME_FORMATTER) : null,
                    section.getEndTime() != null ? section.getEndTime().format(TIME_FORMATTER) : null,
                    taskGroupDTOs,
                    habitGroupDTOs);
        }).collect(Collectors.toList());

        return new DiaryRoutineResponseDTO(
                entity.getId(),
                entity.getName(),
                entity.getIconId(),
                sectionDTOs,
                entity.getSchedule());
    }

    public DiaryRoutineResponseDTO checkGroup(CheckGroupRequestDTO dto, UUID userId) {
        DiaryRoutine routine = getDiaryRoutineModelById(dto.routineId(), userId);
        log.info("STARTING CHECK REQUEST: {}", dto);

        if (dto.habitGroupDTO() != null) {
            return handleHabitGroupCheck(dto, routine);
        } else if (dto.taskGroupDTO() != null) {
            return handleTaskGroupCheck(dto, routine);
        } else {
            throw new IllegalArgumentException("No item group found in the request");
        }
    }

    private DiaryRoutineResponseDTO handleHabitGroupCheck(CheckGroupRequestDTO dto, DiaryRoutine routine) {
        HabitGroup habitGroup = findHabitGroupInRoutine(routine, dto.habitGroupDTO().habitGroupId());
        if (habitGroup == null) throw new RuntimeException("Habit group not found");

        Habit habit = habitGroup.getHabit();
        Optional<HabitGroupCheck> existingCheck = habitGroup.getHabitGroupChecks().stream()
            .filter(c -> c.getCheckDate().equals(LocalDate.now()))
            .findFirst();

        if (existingCheck.isPresent() && existingCheck.get().isChecked()) {
            return uncheckHabit(habitGroup, habit, existingCheck.get(), routine);
        } else {
            return checkHabit(habitGroup, habit, routine);
        }
    }

    private DiaryRoutineResponseDTO checkHabit(HabitGroup group, Habit habit, DiaryRoutine routine) {
        double newXp = 10.0 * habit.getDificulty() * habit.getImportance();
        habit.setXp(habit.getXp() + newXp);
        habit.setConstance(habit.getConstance() + 1);

        if (habit.getXp() >= habit.getNextLevelXp()) {
            levelUpHabit(habit);
        }

        updateCategoriesXpAndLevel(habit.getCategories(), newXp);

        HabitGroupCheck check = new HabitGroupCheck();
        check.setCheckDate(LocalDate.now());
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setXpGenerated(newXp);
        check.setHabitGroup(group);
        group.getHabitGroupChecks().add(check);

        habitService.editEntity(habit);
        updateGroupInRoutine(routine, group);

        log.info("[LOG] GROUP CHECK IN THE ROUTINE TO SAVE => {}", group.getHabitGroupChecks());
        return mapToResponseDTO(diaryRoutineRepository.save(routine));
    }

    private DiaryRoutineResponseDTO uncheckHabit(HabitGroup group, Habit habit, HabitGroupCheck check, DiaryRoutine routine) {
        group.getHabitGroupChecks().remove(check);
        habit.setXp(habit.getXp() - check.getXpGenerated());
        habit.setConstance(habit.getConstance() - 1);

        removeXpFromCategories(habit.getCategories(), check.getXpGenerated());
        habitService.editEntity(habit);
        updateGroupInRoutine(routine, group);

        log.info("[LOG] GROUP CHECK IN THE ROUTINE TO SAVE => {}", group.getHabitGroupChecks());
        return mapToResponseDTO(diaryRoutineRepository.save(routine));
    }

    private HabitGroup findHabitGroupInRoutine(DiaryRoutine routine, UUID id) {
        return routine.getRoutineSections().stream()
            .flatMap(sec -> sec.getHabitGroups().stream())
            .filter(g -> g.getId().equals(id))
            .findFirst()
            .orElse(null);
    }

    private void updateGroupInRoutine(DiaryRoutine routine, HabitGroup updatedGroup) {
        for (RoutineSection section : routine.getRoutineSections()) {
            List<HabitGroup> groups = section.getHabitGroups();
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).getId().equals(updatedGroup.getId())) {
                    log.info("[LOG] UPDATED HABIT GROUP => {}", updatedGroup);
                    groups.set(i, updatedGroup);
                }
            }
        }
    }

    private void levelUpHabit(Habit habit) {
        habit.setLevel(habit.getLevel() + 1);
        XpByLevel actual = xpByLevelRepository.findByLevel(habit.getLevel());
        XpByLevel next = xpByLevelRepository.findByLevel(habit.getLevel() + 1);
        habit.setActualBaseXp(actual.getXp());
        habit.setNextLevelXp(next.getXp());
    }

    //For tasks now
    private DiaryRoutineResponseDTO handleTaskGroupCheck(CheckGroupRequestDTO dto, DiaryRoutine routine) {
        TaskGroup taskGroup = findTaskGroupInRoutine(routine, dto.taskGroupDTO().taskGroupId());
        if (taskGroup == null) throw new RuntimeException("[LOG] Task group not found");
        log.info("[LOG] TaskGROUP found => {}", taskGroup);

        Task task = taskGroup.getTask();
        Optional<TaskGroupCheck> existingCheck = taskGroup.getTaskGroupChecks().stream()
            .filter(c -> c.getCheckDate().equals(LocalDate.now()))
            .findFirst();

        if (existingCheck.isPresent() && existingCheck.get().isChecked()) {
            return uncheckTask(taskGroup, task, existingCheck.get(), routine);
        } else {
            return checkTask(taskGroup, task, routine);
        }
    }

    private DiaryRoutineResponseDTO checkTask(TaskGroup group, Task task, DiaryRoutine routine) {
        int difficulty = task.getDificulty() != null ? task.getDificulty() : 1;
        int importance = task.getImportance() != null ? task.getImportance() : 1;
        double newXp = 10.0 * difficulty * importance;

        TaskGroupCheck check = new TaskGroupCheck();
        check.setCheckDate(LocalDate.now());
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setXpGenerated(0.0);

        if (task.getCategories() != null && !task.getCategories().isEmpty()) {
            updateCategoriesXpAndLevel(task.getCategories(), newXp);
            check.setXpGenerated(newXp);
        }

        check.setTaskGroup(group);
        group.getTaskGroupChecks().add(check);

        taskService.editTask(task);
        updateGroupInRoutine(routine, group);

        log.info("[LOG] GROUP CHECK IN THE ROUTINE TO SAVE => {}", group.getTaskGroupChecks());
        return mapToResponseDTO(diaryRoutineRepository.save(routine));
    }

    private DiaryRoutineResponseDTO uncheckTask(TaskGroup group, Task task, TaskGroupCheck check, DiaryRoutine routine) {
        group.getTaskGroupChecks().remove(check);

        if (task.getCategories() != null && !task.getCategories().isEmpty()) {
            removeXpFromCategories(task.getCategories(), check.getXpGenerated());
        }

        taskService.editTask(task);
        updateGroupInRoutine(routine, group);
        log.info("[LOG] GROUP CHECK IN THE ROUTINE TO SAVE => {}", group.getTaskGroupChecks());
        return mapToResponseDTO(diaryRoutineRepository.save(routine));
    }

    private TaskGroup findTaskGroupInRoutine(DiaryRoutine routine, UUID groupId) {
        return routine.getRoutineSections().stream()
            .flatMap(sec -> sec.getTaskGroups().stream())
            .filter(g -> g.getId().equals(groupId))
            .findFirst()
            .orElse(null);
    }

    private void updateGroupInRoutine(DiaryRoutine routine, TaskGroup updatedGroup) {
        for (RoutineSection section : routine.getRoutineSections()) {
            List<TaskGroup> taskGroups = section.getTaskGroups();
            for (int i = 0; i < taskGroups.size(); i++) {
                if (taskGroups.get(i).getId().equals(updatedGroup.getId())) {
                    log.info("UPDATED TASK GROUP => {}", updatedGroup.getTaskGroupChecks());
                    taskGroups.set(i, updatedGroup);
                }
            }
        }
    }

    private void updateCategoriesXpAndLevel(List<Category> categories, double xpToAdd) {
        for (Category category : categories) {
            double newXp = category.getXp() + xpToAdd;
            category.setXp(newXp);

            while (newXp >= category.getNextLevelXp()) {
                int nextLevel = category.getLevel() + 1;
                XpByLevel nextLevelData = xpByLevelRepository.findByLevel(nextLevel);
                if (nextLevelData == null) break;

                category.setLevel(nextLevel);
                category.setActualLevelXp(category.getNextLevelXp());
                category.setNextLevelXp(nextLevelData.getXp());
            }

            categoryRepository.save(category);
        }
    }

    private void removeXpFromCategories(List<Category> categories, double xpToRemove) {
        for (Category category : categories) {
            double newXp = Math.max(0, category.getXp() - xpToRemove);
            category.setXp(newXp);

            while (newXp < category.getActualLevelXp() && category.getLevel() > 1) {
                int previousLevel = category.getLevel() - 1;
                XpByLevel previousLevelData = xpByLevelRepository.findByLevel(previousLevel);
                if (previousLevelData == null) break;

                category.setLevel(previousLevel);
                category.setNextLevelXp(category.getActualLevelXp());
                category.setActualLevelXp(previousLevelData.getXp());
            }

            categoryRepository.save(category);
        }
    }

}