package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
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
    private final TaskService taskService;
    private final HabitService habitService;
    private final XpByLevelRepository xpByLevelRepository;
    private final CategoryService categoryService;
    private final DiaryRoutineMapper mapper;

    @Transactional(readOnly = true)
    public DiaryRoutineResponseDTO getDiaryRoutineById(UUID id, UUID userId) {
        DiaryRoutine diaryRoutine = diaryRoutineRepository.findById(id)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));
        if(!diaryRoutine.getUser().getId().equals(userId)){
            throw new DiaryRoutineNotFoundException("The user trying to get its different of the one in the object");
        }
        return mapper.toResponse(diaryRoutine);
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
                .map(mapper::toResponse)
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
        DiaryRoutine diaryRoutine = mapper.toEntity(dto);
        diaryRoutine.setUser(user);
        DiaryRoutine saved = diaryRoutineRepository.save(diaryRoutine);
        return mapper.toResponse(saved);
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
        List<RoutineSection> newSections = mapper.mapToRoutineSections(dto.routineSections(), existing);
        existing.getRoutineSections().addAll(newSections);

        DiaryRoutine updated = diaryRoutineRepository.save(existing);
        return mapper.toResponse(updated);
    }

    @Transactional
    public void saveRoutine(DiaryRoutine routine){
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
            if(diaryRoutine.getSchedule() != null && diaryRoutine.getSchedule().getDays().contains(WeekDay.valueOf(dayOfWeek))){
                log.info("Routine {} are scheduled for today", diaryRoutine.getName());
                todaysRoutine = diaryRoutine;
            }
        }

        if(todaysRoutine == null){
            log.warn("NO ROUTINES SCHEDULED FOR TODAY");
            return null;
        }else{
            return mapper.toResponse(todaysRoutine);
        }

    }

    public void updateRoutineXpAndLevel(DiaryRoutine diaryRoutine, Double newXp){
        diaryRoutine.setXp(diaryRoutine.getXp() + newXp);

        if(diaryRoutine.getXp() >= diaryRoutine.getNextLevelXp()){
            diaryRoutine.setLevel(diaryRoutine.getLevel() + 1);
            XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(diaryRoutine.getLevel());
            XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(diaryRoutine.getLevel() + 1);
            diaryRoutine.setActualBaseXp(xpForActualLevel.getXp());
            diaryRoutine.setNextLevelXp(xpForNextLevel.getXp());
        }

        diaryRoutineRepository.save(diaryRoutine);
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

    public DiaryRoutineResponseDTO checkAndUncheckGroup(CheckGroupRequestDTO checkGroupRequestDTO, UUID userId){
        LocalDate date = checkGroupRequestDTO.date() != null ? checkGroupRequestDTO.date() : LocalDate.now();
        if(checkGroupRequestDTO.habitGroupDTO() != null){
            HabitGroup habitGroupToCheckOrUncheck = findHabitGroupByDTO(checkGroupRequestDTO);
            checkOrUncheckHabitGroup(habitGroupToCheckOrUncheck, date);

            //After all the updates, return the fresh state of routine with all the modification
            return getDiaryRoutineById(checkGroupRequestDTO.routineId(), userId);
        }else if(checkGroupRequestDTO.taskGroupDTO() != null){
            TaskGroup taskGroupToCheckOrUncheck = findTaskGroupByDTO(checkGroupRequestDTO);
            checkOrUncheckTaskGroup(taskGroupToCheckOrUncheck, date);

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

    protected void checkOrUncheckHabitGroup(HabitGroup habitGroupToCheckOrUncheck, LocalDate date) {
        // Check if the habit group is already checked for today
        boolean isCheckedToday = habitGroupToCheckOrUncheck.getHabitGroupChecks().stream()
        .peek(hc -> log.info("Evaluating check: date={}, checked={}", hc.getCheckDate(), hc.isChecked()))
        .anyMatch(habitCheck -> habitCheck.getCheckDate().equals(date) && habitCheck.isChecked());

        if(isCheckedToday){
            // Uncheck: Remove check, subtract XP, adjust constance
            uncheckHabitGroup(habitGroupToCheckOrUncheck, date);
        }else{
            //Calculate the exp (Think in a good algorithm later on)
            checkHabitGroup(habitGroupToCheckOrUncheck, date);
        }
    }

    protected void checkHabitGroup(HabitGroup habitGroupToCheckOrUncheck, LocalDate date){
        DiaryRoutine routine = (DiaryRoutine) habitGroupToCheckOrUncheck.getRoutineSection().getRoutine();
        log.info("[LOG] Starting Check");
        Habit habitChecked = habitGroupToCheckOrUncheck.getHabit();
        HabitGroupCheck check = null;

        check = checkIfHabitGroupIsAlreadyCheckedAndOverride(habitGroupToCheckOrUncheck, date);

        Double newXp = (double) (10 * habitChecked.getDificulty() * habitChecked.getImportance());
        habitChecked.getXpProgress().setXp(newXp + habitChecked.getXpProgress().getXp());
        if(newXp > habitChecked.getXpProgress().getNextLevelXp()){
            habitChecked.getXpProgress().setLevel(habitChecked.getXpProgress().getLevel() + 1);
            XpByLevel xpForActualLevel = xpByLevelRepository.findByLevel(habitChecked.getXpProgress().getLevel());
            XpByLevel xpForNextLevel = xpByLevelRepository.findByLevel(habitChecked.getXpProgress().getLevel() + 1);
            habitChecked.getXpProgress().setActualLevelXp(xpForActualLevel.getXp());
            habitChecked.getXpProgress().setNextLevelXp(xpForNextLevel.getXp());
        }            
        //1 more for the constance
        habitChecked.setConstance(habitChecked.getConstance() + 1);

        //Update categories xp
        List<Category> categories = habitChecked.getCategories();
        categoryService.updateCategoriesXpAndLevel(categories, newXp);

        //Set check object
        check.setCheckDate(date);
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

    private HabitGroupCheck checkIfHabitGroupIsAlreadyCheckedAndOverride(HabitGroup habitGroup, LocalDate date){
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

    protected void uncheckHabitGroup(HabitGroup habitGroupToUncheck, LocalDate date){
        DiaryRoutine routine = (DiaryRoutine) habitGroupToUncheck.getRoutineSection().getRoutine();
        HabitGroupCheck existingCheck = habitGroupToUncheck.getHabitGroupChecks().stream()
                .filter(habitCheck -> habitCheck.getCheckDate().equals(date))
                .findFirst()
                .get();
        Habit habitToCheck = habitGroupToUncheck.getHabit();
        log.info("[LOG] Starting Uncheck for HabitGroupCheck => {}", existingCheck);

        habitGroupToUncheck.getHabitGroupChecks().remove(existingCheck);
        habitToCheck.getXpProgress().setXp(habitToCheck.getXpProgress().getXp() - existingCheck.getXpGenerated());
        habitToCheck.setConstance(habitToCheck.getConstance() - 1);

        categoryService.removeXpFromCategories(habitToCheck.getCategories(), existingCheck.getXpGenerated());
        habitService.editEntity(habitToCheck);

        existingCheck.setCheckDate(date);
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

    protected void checkOrUncheckTaskGroup(TaskGroup taskGroupToCheckOrUncheck, LocalDate date) {
        // Check if the habit group is already checked for today
        boolean isCheckedToday = taskGroupToCheckOrUncheck.getTaskGroupChecks().stream()
        .anyMatch(habitCheck -> habitCheck.getCheckDate().equals(date) && habitCheck.isChecked());

        if(isCheckedToday){
            // Uncheck: Remove check, subtract XP, adjust constance
            uncheckTaskGroup(taskGroupToCheckOrUncheck, date);
        }else{
            //Calculate the exp (Think in a good algorithm later on)
            checkTaskGroup(taskGroupToCheckOrUncheck, date);
        }
    }

    protected void checkTaskGroup(TaskGroup taskGroupToCheck, LocalDate date){
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

    protected void uncheckTaskGroup(TaskGroup taskGroupUnchecked, LocalDate date){
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
            categoryService.removeXpFromCategories(taskChecked.getCategories(), existingCheck.getXpGenerated());
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
        diaryRoutineRepository.save(routine);
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