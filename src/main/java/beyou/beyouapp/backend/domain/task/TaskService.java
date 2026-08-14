package beyou.beyouapp.backend.domain.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.task.TaskNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaskService {
    private final TaskRepository taskRepository;
    private final UserRepository userRepository;
    private final CategoryService categoryService;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final TaskMapper taskMapper;
    private final UserCacheEvictService userCacheEvictService;
    private final EntityCheckDayRepository entityCheckDayRepository;

    public Task getTask(UUID taskId){
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFound("Task not found"));
    }

    // Transactional so the mapper can walk lazy category relations: OSIV covers
    // this on the request thread, but agent tools run on a boundedElastic thread.
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "tasks", key = "#userId")
    public List<TaskResponseDTO> getAllTasks(UUID userId){
        List<Task> tasks = taskRepository.findAllByUserId(userId).orElseThrow(() -> new UserNotFound("User not found when tried to get tasks"));
        return tasks.stream().map(taskMapper::toResponseDTO).toList();
    }

    /**
     * Authoritative cleanup filter: a marked task dies only once the day it was marked has
     * passed in ITS OWNER's timezone. The scheduler's query is only a coarse pre-filter, so
     * this re-check is what actually decides — never widen it back to a server-zone date.
     *
     * <p>R8 — the same history delete {@link #deleteTask} does. Every task this method can
     * reach is a one-time task ({@code markedToDelete} is only ever set by
     * {@code CheckItemService} behind an {@code isOneTimeTask()} guard), and one-time tasks
     * never accumulate rows — all three writers skip them (R4/KTD14). So the delete finds
     * nothing today, and it is here anyway: the exemption then lives in exactly one place,
     * the writers, instead of being re-derived at every delete site. The alternative is a
     * second copy of the rule that goes quietly wrong the day the writers stop applying it.
     *
     * <p>Transactional for the same reason {@code deleteTask} is — the bulk delete has no
     * transaction of its own. The only production caller, {@code TaskCleanupScheduler}, is
     * already transactional and this simply joins it; the annotation is what keeps that true
     * if a second caller ever appears.
     */
    @Transactional
    public void deleteAllMarked(List<Task> tasks, UUID userId) {
        List<Task> tasksToDelete = tasks.stream()
            .filter(task -> task.getMarkedToDelete() != null
                && task.getMarkedToDelete().isBefore(UserDateResolver.today(task.getUser())))
            .collect(Collectors.toList());

        log.info("Tasks to be deleted => {}", tasksToDelete);

        if (tasksToDelete.isEmpty()) return;
        
        Set<UUID> deletedTaskIds = tasksToDelete.stream()
            .map(Task::getId)
            .collect(Collectors.toSet());
        
        log.info("Deleted task ids => {}", deletedTaskIds);

        //First remove from routines
        List<DiaryRoutine> diaryRoutines = diaryRoutineRepository.findAllByUserId(userId);
        for (DiaryRoutine diaryRoutine : diaryRoutines) {
            boolean modified = false;

            for (RoutineSection section : diaryRoutine.getRoutineSections()) {
                boolean removed = section.getTaskGroups().removeIf(
                    group -> deletedTaskIds.contains(group.getTask().getId())
                );
                log.info("Task group to delete is part of section => {}", removed);

                if (removed) {
                    modified = true;
                }
            }

            if (modified) {
                log.info("Diary routine modified => {}", diaryRoutine);
                diaryRoutineRepository.save(diaryRoutine);
            }
        }

        deletedTaskIds.forEach(taskId ->
            entityCheckDayRepository.deleteAllByOwner(CheckDayOwnerType.TASK, taskId));

        //Then delete from the respotisory
        taskRepository.deleteAll(tasksToDelete);
    }


    /** Core create: saves and returns the entity. Does NOT evict caches — callers decide. */
    public Task createTaskEntity(CreateTaskRequestDTO createTaskDTO, UUID userId){
        User user = userRepository.findById(userId).orElseThrow(() ->
        new UserNotFound("User not found when tried to create a task"));

        List<Category> categoriesToAdd = new ArrayList<>();

        if(createTaskDTO.categoriesId() != null && !createTaskDTO.categoriesId().isEmpty()){
            // Dedupe ids so a task never gets the same category (and join row) twice.
            List<UUID> categoriesId = createTaskDTO.categoriesId().stream().distinct().toList();
            categoriesId.forEach(categoryId ->
            categoriesToAdd.add(categoryService.getCategory(categoryId, userId)));
        }

        Task taskToCreate = taskMapper.toEntity(createTaskDTO, categoriesToAdd, user);

        try {
            return taskRepository.save(taskToCreate);
        } catch (Exception e) {
            log.error("Error trying to create task", e);
            throw new BusinessException(ErrorKey.TASK_CREATE_FAILED, "Error trying to create task");
        }
    }

    public ResponseEntity<Map<String, String>> createTask(CreateTaskRequestDTO createTaskDTO, UUID userId){
        createTaskEntity(createTaskDTO, userId);
        userCacheEvictService.evictAllUserCaches(userId);
        return ResponseEntity.ok().body(Map.of("success", "Task created Successfully"));
    }

    public ResponseEntity<Map<String, String>> editTask(EditTaskRequestDTO editTaskRequestDTO, UUID userId){
        Task taskToEdit = getTask(editTaskRequestDTO.taskId());

        if(!taskToEdit.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorKey.TASK_NOT_OWNED, "The task isn't of the user on context");
        }

        taskToEdit.setName(editTaskRequestDTO.name());
        taskToEdit.setDescription(editTaskRequestDTO.description());
        taskToEdit.setIconId(editTaskRequestDTO.iconId());
        taskToEdit.setImportance(editTaskRequestDTO.importance());
        taskToEdit.setDificulty(editTaskRequestDTO.difficulty());
        
        List<Category> categoriesToAdd = new ArrayList<>();
        if(editTaskRequestDTO.categoriesId() != null && !editTaskRequestDTO.categoriesId().isEmpty()){
            // Dedupe ids so a task never gets the same category (and join row) twice.
            List<UUID> categoriesId = editTaskRequestDTO.categoriesId().stream().distinct().toList();
            categoriesId.forEach(categoryId ->
            categoriesToAdd.add(categoryService.getCategory(categoryId, userId)));
        }
        taskMapper.updateEntity(taskToEdit, editTaskRequestDTO, categoriesToAdd);

        try{
            taskRepository.save(taskToEdit);
            userCacheEvictService.evictAllUserCaches(userId);
            return ResponseEntity.ok().body(Map.of("success", "Task edited successfully"));
        }catch(TaskNotFound e){
            throw e;
        }catch(Exception e){
            throw new BusinessException(ErrorKey.TASK_EDIT_FAILED, "Error trying to edit task");
        }
    }

    /**
     * R8/KTD24 — deleting the task deletes its day history with it, the same asymmetry
     * {@code HabitService.deleteHabit} documents: the routine that held it has no say, this
     * does. {@code @Transactional} is required, not cosmetic — {@code deleteAllByOwner} is a
     * bulk {@code @Modifying} query and throws without a transaction to run in.
     */
    @Transactional
    public ResponseEntity<Map<String, String>> deleteTask(UUID taskId, UUID userId){
        Task taskToDelete = getTask(taskId);
        log.info("[LOG] Deleting task => {}", taskToDelete);
        if(!taskToDelete.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorKey.TASK_NOT_OWNED, "The task isn't of the user on context");
        }

        if (isTaskLinkedToRoutine(taskId, userId)) {
            throw new BusinessException(ErrorKey.TASK_IN_ROUTINE, "This task is used in some routine, please remove it first");
        }

        try{
            int removedDays = entityCheckDayRepository.deleteAllByOwner(CheckDayOwnerType.TASK, taskId);
            log.info("Removed {} check-day rows for task {}", removedDays, taskId);
            taskRepository.delete(taskToDelete);
            userCacheEvictService.evictAllUserCaches(userId);
            return ResponseEntity.ok(Map.of("success", "Task deleted Successfully!"));
        }catch(DataIntegrityViolationException e){
            throw new BusinessException(ErrorKey.TASK_IN_ROUTINE, "This task is used in some routine, please remove it first");
        }catch(Exception e){
            throw new BusinessException(ErrorKey.TASK_DELETE_FAILED, "Error trying to delete task");
        }
    }

    private boolean isTaskLinkedToRoutine(UUID taskId, UUID userId) {
        List<DiaryRoutine> routines = diaryRoutineRepository.findAllByUserId(userId);
        return routines.stream()
            .flatMap(routine -> routine.getRoutineSections().stream())
            .flatMap(section -> section.getTaskGroups().stream())
            .anyMatch(group -> group.getTask() != null && taskId.equals(group.getTask().getId()));
    }

    public Task editTask(Task taskToEdit){
        return taskRepository.save(taskToEdit);
    }
}
