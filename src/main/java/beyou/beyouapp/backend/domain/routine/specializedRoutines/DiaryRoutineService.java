package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final CategoryService categoryService;

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
        AtomicInteger index = new AtomicInteger(0);
        return dtos.stream().map(dto -> {
            RoutineSection section = new RoutineSection();
            log.info("ID OF SECTION ITEM =» {}", dto.id());
            if(dto.id() != null){
                section.setId(dto.id());
            }
            section.setOrderIndex(index.getAndIncrement());
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

    public DiaryRoutineResponseDTO checkAndUncheckGroup(CheckGroupRequestDTO checkGroupRequestDTO, UUID userId){
        if(checkGroupRequestDTO.habitGroupDTO() != null){
            HabitGroup habitGroupToCheckOrUncheck = findHabitGroupByDTO(checkGroupRequestDTO);
            checkOrUncheckHabitGroup(habitGroupToCheckOrUncheck);

            //After all the updates, return the fresh state of routine with all the modification
            return getDiaryRoutineById(checkGroupRequestDTO.routineId(), userId);
        }else if(checkGroupRequestDTO.taskGroupDTO() != null){
            TaskGroup taskGroupToCheckOrUncheck = findTaskGroupByDTO(checkGroupRequestDTO);
            checkOrUncheckTaskGroup(taskGroupToCheckOrUncheck);

            //After all the updates, return the fresh state of routine with all the modification
            return getDiaryRoutineById(checkGroupRequestDTO.routineId(), userId);
        }else{
            throw new RuntimeException("No Item group found in the request");
        }
    }

    protected HabitGroup findHabitGroupByDTO(CheckGroupRequestDTO habitGroupToCheckOrUncheck) {
        DiaryRoutine routine = diaryRoutineRepository.findById(habitGroupToCheckOrUncheck.routineId()).orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        return routine.getRoutineSections().stream()
            .flatMap(section -> section.getHabitGroups().stream())
            .filter(habitGroup -> habitGroup.getId().equals(habitGroupToCheckOrUncheck.habitGroupDTO().habitGroupId()))
            .findFirst()
            .orElse(null);
    }

    protected void checkOrUncheckHabitGroup(HabitGroup habitGroupToCheckOrUncheck) {
        // Check if the habit group is already checked for today
        boolean isCheckedToday = habitGroupToCheckOrUncheck.getHabitGroupChecks().stream()
        .peek(hc -> log.info("Evaluating check: date={}, checked={}", hc.getCheckDate(), hc.isChecked()))
        .anyMatch(habitCheck -> habitCheck.getCheckDate().equals(LocalDate.now()) && habitCheck.isChecked());

        if(isCheckedToday){
            // Uncheck: Remove check, subtract XP, adjust constance
            uncheckHabitGroup(habitGroupToCheckOrUncheck);
        }else{
            //Calculate the exp (Think in a good algorithm later on)
            checkHabitGroup(habitGroupToCheckOrUncheck);
        }
    }

    protected void checkHabitGroup(HabitGroup habitGroupToCheckOrUncheck){
        DiaryRoutine routine = (DiaryRoutine) habitGroupToCheckOrUncheck.getRoutineSection().getRoutine();
        log.info("[LOG] Starting Check");
        Habit habitChecked = habitGroupToCheckOrUncheck.getHabit();
        HabitGroupCheck check = null;

        check = checkIfHabitGroupIsAlreadyCheckedAndOverride(habitGroupToCheckOrUncheck);

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
        categoryService.updateCategoriesXpAndLevel(categories, newXp);

        //Set check object
        check.setCheckDate(LocalDate.now());
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setXpGenerated(newXp);
        check.setHabitGroup(habitGroupToCheckOrUncheck);

        habitGroupToCheckOrUncheck.getHabitGroupChecks().add(check);

        //Update entities
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
        diaryRoutineRepository.save(routine);
    }

    private HabitGroupCheck checkIfHabitGroupIsAlreadyCheckedAndOverride(HabitGroup habitGroup){
        Optional<HabitGroupCheck> existingCheck = habitGroup.getHabitGroupChecks().stream()
            .filter(tc -> tc.getCheckDate().equals(LocalDate.now()))
            .findFirst();

        if (existingCheck.isPresent()) {
            log.info("[LOG] TaskGroup already have check for today, overriting this one => {}", habitGroup);
            habitGroup.getHabitGroupChecks().remove(existingCheck.get());
            return existingCheck.get();
        }
        return new HabitGroupCheck();
    }

    protected void uncheckHabitGroup(HabitGroup habitGroupToUncheck){
        DiaryRoutine routine = (DiaryRoutine) habitGroupToUncheck.getRoutineSection().getRoutine();
        HabitGroupCheck existingCheck = habitGroupToUncheck.getHabitGroupChecks().stream()
                .filter(habitCheck -> habitCheck.getCheckDate().equals(LocalDate.now()))
                .findFirst()
                .get();
        Habit habitToCheck = habitGroupToUncheck.getHabit();
        log.info("[LOG] Starting Uncheck for HabitGroupCheck => {}", existingCheck);

        habitGroupToUncheck.getHabitGroupChecks().remove(existingCheck);
        habitToCheck.setXp(habitToCheck.getXp() - existingCheck.getXpGenerated());
        habitToCheck.setConstance(habitToCheck.getConstance() - 1);

        categoryService.removeXpFromCategories(habitToCheck.getCategories(), existingCheck.getXpGenerated());
        habitService.editEntity(habitToCheck);

        existingCheck.setCheckDate(LocalDate.now());
        existingCheck.setCheckTime(LocalTime.now());
        existingCheck.setChecked(false);
        existingCheck.setXpGenerated(0);
        habitGroupToUncheck.getHabitGroupChecks().add(existingCheck);
        diaryRoutineRepository.save(routine);
    }

    protected TaskGroup findTaskGroupByDTO(CheckGroupRequestDTO taskGroupToCheckOrUncheck){
        DiaryRoutine routine = diaryRoutineRepository.findById(taskGroupToCheckOrUncheck.routineId()).orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        return routine.getRoutineSections().stream()
            .flatMap(section -> section.getTaskGroups().stream())
            .filter(taskGroup -> taskGroup.getId().equals(taskGroupToCheckOrUncheck.taskGroupDTO().taskGroupId()))
            .findFirst()
            .orElse(null);
    }

    protected void checkOrUncheckTaskGroup(TaskGroup taskGroupToCheckOrUncheck) {
        // Check if the habit group is already checked for today
        boolean isCheckedToday = taskGroupToCheckOrUncheck.getTaskGroupChecks().stream()
        .anyMatch(habitCheck -> habitCheck.getCheckDate().equals(LocalDate.now()) && habitCheck.isChecked());

        if(isCheckedToday){
            // Uncheck: Remove check, subtract XP, adjust constance
            uncheckTaskGroup(taskGroupToCheckOrUncheck);
        }else{
            //Calculate the exp (Think in a good algorithm later on)
            checkTaskGroup(taskGroupToCheckOrUncheck);
        }
    }

    protected void checkTaskGroup(TaskGroup taskGroupToCheck){
        DiaryRoutine routine = (DiaryRoutine) taskGroupToCheck.getRoutineSection().getRoutine();
        log.info("[LOG] Starting Check");
        Task taskChecked = taskGroupToCheck.getTask();
        TaskGroupCheck check = new TaskGroupCheck();

        check = checkIfTaskGroupIsAlreadyCheckedAndOverrideCheck(taskGroupToCheck);

        int dificulty = taskChecked.getDificulty() != null ? taskChecked.getDificulty() : 1;
        int importance = taskChecked.getImportance() != null ? taskChecked.getImportance() : 1;
        Double newXp = (double) (10 * dificulty * importance);

        //Set check object
        check.setCheckDate(LocalDate.now());
        check.setCheckTime(LocalTime.now());
        check.setChecked(true);
        check.setXpGenerated(0);
        check.setTaskGroup(taskGroupToCheck);

        //Update categories
        if(taskChecked.getCategories() != null && taskChecked.getCategories().size() > 0){
            log.info("Task have category");
            check.setXpGenerated(newXp);
            List<Category> categories = taskChecked.getCategories();
            categoryService.updateCategoriesXpAndLevel(categories, newXp);
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
        diaryRoutineRepository.save(routine);
    }

    protected void uncheckTaskGroup(TaskGroup taskGroupUnchecked){
        DiaryRoutine routine = (DiaryRoutine) taskGroupUnchecked.getRoutineSection().getRoutine();
        log.info("[LOG] Starting unchecking");
        Task taskChecked = taskGroupUnchecked.getTask();

        TaskGroupCheck existingCheck = taskGroupUnchecked.getTaskGroupChecks().stream()
            .filter(taskCheck -> taskCheck.getCheckDate().equals(LocalDate.now()))
            .findFirst()
            .get();

        //Clean the check and remove the xp generated in the categories
        taskGroupUnchecked.getTaskGroupChecks().remove(existingCheck);
        if(taskChecked.getCategories() != null && taskChecked.getCategories().size() > 0){
            categoryService.removeXpFromCategories(taskChecked.getCategories(), existingCheck.getXpGenerated());
        }

        //Remove marked to delete if has
        if(taskChecked.isOneTimeTask()){
            taskChecked.setMarkedToDelete(null);
        }

        taskService.editTask(taskChecked);

        existingCheck.setCheckDate(LocalDate.now());
        existingCheck.setCheckTime(LocalTime.now());
        existingCheck.setChecked(false);
        existingCheck.setXpGenerated(0);
        taskGroupUnchecked.getTaskGroupChecks().add(existingCheck);
        diaryRoutineRepository.save(routine);
    }

    private TaskGroupCheck checkIfTaskGroupIsAlreadyCheckedAndOverrideCheck(TaskGroup taskGroup){
        Optional<TaskGroupCheck> existingCheck = taskGroup.getTaskGroupChecks().stream()
            .filter(tc -> tc.getCheckDate().equals(LocalDate.now()))
            .findFirst();

        if (existingCheck.isPresent()) {
            log.info("[LOG] TaskGroup already have check for today, overriting this one => {}", taskGroup);
            taskGroup.getTaskGroupChecks().remove(existingCheck.get());
            return existingCheck.get();
        }
        return new TaskGroupCheck();
    }
}