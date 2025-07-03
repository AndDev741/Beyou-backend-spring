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
            if(diaryRoutine.getSchedule() != null && diaryRoutine.getSchedule().getDays().contains(dayOfWeek)){
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

                 if(habitDto.id() != null){
                    habitGroup.setId(habitDto.id());
                }

                if (habitDto.habitGroupCheck() != null) {
                    List<HabitGroupCheck> checks = habitGroup.getHabitGroupChecks();
                    checks.addAll(habitDto.habitGroupCheck());
                    habitGroup.setHabitGroupChecks(checks);
                }
                // for (RoutineSection routineSection : diaryRoutine.getRoutineSections()) {
                //     List<HabitGroup> actualHabitGroups = routineSection.getHabitGroups();
                //     for (int i = 0; i < actualHabitGroups.size(); i++) {
                //         actualHabitGroups.get(i).getHabitGroupChecks().clear();
                //     }
                // }

               
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

    public DiaryRoutineResponseDTO checkGroup(CheckGroupRequestDTO checkGroupRequestDTO, UUID userId){
        DiaryRoutine routine = getDiaryRoutineModelById(checkGroupRequestDTO.routineId(), userId);

        HabitGroup habitGroupToCheck = null;

        TaskGroup taskGroupToCheck = null;

        if(checkGroupRequestDTO.habitGroupDTO() != null){
            for(RoutineSection section : routine.getRoutineSections()){
                List<HabitGroup> habitGroups = section.getHabitGroups();
                for (int i = 0; i < habitGroups.size(); i++) {
                    HabitGroup current = habitGroups.get(i);
                    if (current.getId().equals(checkGroupRequestDTO.habitGroupDTO().habitGroupId())) {
                        log.info("[LOG] Found the habitGroup => {}", current);
                        habitGroupToCheck = current;
                    }
                }
            }
        }else if(checkGroupRequestDTO.taskGroupDTO() != null){
            for(RoutineSection section : routine.getRoutineSections()){
                List<TaskGroup> taskGroups = section.getTaskGroups();
                for (int i = 0; i < taskGroups.size(); i++) {
                    TaskGroup current = taskGroups.get(i);
                    if (current.getId().equals(checkGroupRequestDTO.taskGroupDTO().taskGroupId())) {
                        log.info("[LOG] Found the TaskGroup  => {}", current);
                        taskGroupToCheck = current;
                    }
                }
            }
        }else{
            throw new RuntimeException("No Item group found in the request");
        }

        log.info("[LOG] Item group found => {}", habitGroupToCheck == null ? taskGroupToCheck : habitGroupToCheck);

        if(habitGroupToCheck != null){
            Habit habitChecked = habitGroupToCheck.getHabit();
            HabitGroupCheck check = new HabitGroupCheck();

            if (habitGroupToCheck.getHabitGroupChecks().stream()
                    .anyMatch(habitCheck -> habitCheck.getCheckDate().equals(LocalDate.now()) && habitCheck.isChecked())) {
                // Uncheck: Remove check, subtract XP, adjust constance
                HabitGroupCheck existingCheck = habitGroupToCheck.getHabitGroupChecks().stream()
                    .filter(habitCheck -> habitCheck.getCheckDate().equals(LocalDate.now()))
                    .findFirst()
                    .get();
                log.info("[LOG] Starting Uncheck for HabitGroupCheck => {}", existingCheck);
                habitGroupToCheck.getHabitGroupChecks().remove(existingCheck);
                habitChecked.setXp(habitChecked.getXp() - existingCheck.getXpGenerated());
                habitChecked.setConstance(habitChecked.getConstance() - 1);

                removeXpFromCategories(habitChecked.getCategories(), existingCheck.getXpGenerated());
                habitService.editEntity(habitChecked);

                existingCheck.setCheckDate(LocalDate.now());
                existingCheck.setCheckTime(LocalTime.now());
                existingCheck.setChecked(false);
                existingCheck.setXpGenerated(0);
                habitGroupToCheck.getHabitGroupChecks().add(existingCheck);

                DiaryRoutine routineUpdated = diaryRoutineRepository.save(routine);
                log.info("[LOG] Routine Updated after uncheck => {}", routineUpdated);
                return mapToResponseDTO(routineUpdated);
            }else{
                //Calculate the exp (Think in a good algorithm later on)
                log.info("[LOG] Starting Check");

                HabitGroupCheck habitGroupCheck = null;

                if(habitGroupToCheck.getHabitGroupChecks().stream()
                    .filter(habitCheck -> habitCheck.getCheckDate().equals(LocalDate.now()))
                    .findFirst()
                    .isPresent()){
                        log.info("[LOG] HabitGroup already have check for today, overriting this one 0> {}", habitGroupCheck);
                        habitGroupCheck = habitGroupToCheck.getHabitGroupChecks().stream()
                            .filter(habitCheck -> habitCheck.getCheckDate().equals(LocalDate.now()))
                            .findFirst()
                            .get();
                        habitGroupToCheck.getHabitGroupChecks().remove(habitGroupCheck);
                        check = habitGroupCheck;
                }

                Double newXp = (double) (10 * habitChecked.getDificulty() * habitChecked.getImportance());
                habitChecked.setXp(newXp + habitChecked.getXp());
                if(newXp > habitChecked.getNextLevelXp()){
                    habitChecked.setLevel(habitChecked.getLevel() + 1);
                    XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(habitChecked.getLevel());
                    XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(habitChecked.getLevel() + 1);
                    habitChecked.setActualBaseXp(xpForActualLevel.getXp());
                    habitChecked.setNextLevelXp(xpForNextLevel.getXp());
                }            
                //1 more for the constance
                habitChecked.setConstance(habitChecked.getConstance() + 1);

                //Update categories xp
                List<Category> categories = habitChecked.getCategories();
                updateCategoriesXpAndLevel(categories, newXp);

                //Set check object
                check.setCheckDate(LocalDate.now());
                check.setCheckTime(LocalTime.now());
                check.setChecked(true);
                check.setXpGenerated(newXp);
                check.setHabitGroup(habitGroupToCheck);

                habitGroupToCheck.getHabitGroupChecks().add(check);

                //Update entities
                habitService.editEntity(habitChecked);
                for (RoutineSection section : routine.getRoutineSections()) {
                    List<HabitGroup> habitGroups = section.getHabitGroups();
                    for (int i = 0; i < habitGroups.size(); i++) {
                        HabitGroup current = habitGroups.get(i);
                        if (current.getId().equals(habitGroupToCheck.getId())) {
                            habitGroups.set(i, habitGroupToCheck);
                        }
                    }
                }

                DiaryRoutine routineUpdated = diaryRoutineRepository.save(routine);
                return mapToResponseDTO(routineUpdated);
            }
            
        }

        if(taskGroupToCheck != null){
            Task taskChecked = taskGroupToCheck.getTask();
            TaskGroupCheck check = new TaskGroupCheck();

            if (taskGroupToCheck.getTaskGroupChecks().stream()
                    .anyMatch(taskCheck -> taskCheck.getCheckDate().equals(LocalDate.now()) && taskCheck.isChecked())) {
                // Uncheck: Remove check, subtract XP, adjust constance
                TaskGroupCheck existingCheck = taskGroupToCheck.getTaskGroupChecks().stream()
                    .filter(taskCheck -> taskCheck.getCheckDate().equals(LocalDate.now()))
                    .findFirst()
                    .get();
                taskGroupToCheck.getTaskGroupChecks().remove(existingCheck);
                if(taskChecked.getCategories() != null && taskChecked.getCategories().size() > 0){
                    removeXpFromCategories(taskChecked.getCategories(), existingCheck.getXpGenerated());
                }

                taskService.editTask(taskChecked);
                if (taskChecked.isOneTimeTask()) {
                    for (RoutineSection section : routine.getRoutineSections()) {
                        section.getTaskGroups().removeIf(group -> group.getId().equals(check.getTaskGroup().getId()));
                    }
                    taskService.deleteTask(taskChecked.getId(), userId);
                }
                DiaryRoutine routineUpdated = diaryRoutineRepository.save(routine);
                return mapToResponseDTO(routineUpdated);
            }else{
                //Calculate the exp (Think in a good algorithm later on)
                int dificulty = taskChecked.getDificulty() != null ? taskChecked.getDificulty() : 1;
                int importance = taskChecked.getImportance() != null ? taskChecked.getImportance() : 1;
                Double newXp = (double) (10 * dificulty * importance);

                //Set check object
                check.setCheckDate(LocalDate.now());
                check.setCheckTime(LocalTime.now());
                check.setChecked(true);
                check.setXpGenerated(0);
                check.setTaskGroup(taskGroupToCheck);

                if(taskChecked.getCategories() != null && taskChecked.getCategories().size() > 0){
                    log.info("Task have category");
                    check.setXpGenerated(newXp);
                    List<Category> categories = taskChecked.getCategories();
                    updateCategoriesXpAndLevel(categories, newXp);
                }

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
                DiaryRoutine routineUpdated = diaryRoutineRepository.save(routine);
                return mapToResponseDTO(routineUpdated);
            }
            }

        return null;
    }

    private void updateCategoriesXpAndLevel(List<Category> categories, Double newXp){
        for(Category category : categories){
            category.setXp(category.getXp() + newXp);

            if(category.getXp() > category.getNextLevelXp() || category.getXp() == category.getNextLevelXp()){
                category.setLevel(category.getLevel() + 1);
                XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(category.getLevel());
                XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(category.getLevel() + 1);
                category.setActualLevelXp(xpForActualLevel.getXp());
                category.setNextLevelXp(xpForNextLevel.getXp());
            }

            categoryRepository.save(category);
        }
    }

    private void removeXpFromCategories(List<Category> categories, Double xpToRemove){
        for(Category category : categories){
            log.info("[LOG] Xp to remove => {}", xpToRemove);
            category.setXp(category.getXp() - xpToRemove);
            if(category.getXp() < category.getActualLevelXp() && category.getXp() > 0 ){
                category.setLevel(category.getLevel() - 1);
                XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(category.getLevel());
                XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(category.getLevel() + 1);
                category.setActualLevelXp(xpForActualLevel.getXp());
                category.setNextLevelXp(xpForNextLevel.getXp());
            }

            categoryRepository.save(category);
        }
    }
}