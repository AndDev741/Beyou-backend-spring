package beyou.beyouapp.backend.domain.task;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
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
import jakarta.transaction.Transactional;
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

    public Task getTask(UUID taskId){
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFound("Task not found"));
    }

    public List<TaskResponseDTO> getAllTasks(UUID userId){
        List<Task> tasks = taskRepository.findAllByUserId(userId).orElseThrow(() -> new UserNotFound("User not found when tried to get tasks"));
        deleteAllMarked(tasks, userId);
        return taskRepository.findAllByUserId(userId)
                .orElseThrow(() -> new UserNotFound("User not found when tried to get tasks"))
                .stream()
                .map(taskMapper::toResponseDTO)
                .toList();
    }

    @Transactional
    private void deleteAllMarked(List<Task> tasks, UUID userId) {
        LocalDate today = LocalDate.now();
        
        List<Task> tasksToDelete = tasks.stream()
            .filter(task -> task.getMarkedToDelete() != null && task.getMarkedToDelete().isBefore(today))
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

        //Then delete from the respotisory
        taskRepository.deleteAll(tasksToDelete);
    }


    public ResponseEntity<Map<String, String>> createTask(CreateTaskRequestDTO createTaskDTO, UUID userId){
        User user = userRepository.findById(userId).orElseThrow(() -> 
        new UserNotFound("User not found when tried to create a task"));

        List<Category> categoriesToAdd = new ArrayList<>();

        if(createTaskDTO.categoriesId() != null && !createTaskDTO.categoriesId().isEmpty()){
            List<UUID> categoriesId = createTaskDTO.categoriesId();
            categoriesId.forEach(categoryId -> 
            categoriesToAdd.add(categoryService.getCategory(categoryId)));
        }
        
        Task taskToCreate = taskMapper.toEntity(createTaskDTO, categoriesToAdd, user);

        try {
            taskRepository.save(taskToCreate);
            return ResponseEntity.ok().body(Map.of("success", "Task created Successfully"));
        } catch (Exception e) {
            log.error("Error trying to create task", e);
            throw new BusinessException(ErrorKey.TASK_CREATE_FAILED, "Error trying to create task");
        }
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
            List<UUID> categoriesId = editTaskRequestDTO.categoriesId();
            categoriesId.forEach(categoryId -> 
            categoriesToAdd.add(categoryService.getCategory(categoryId)));
        }
        taskMapper.updateEntity(taskToEdit, editTaskRequestDTO, categoriesToAdd);

        try{
            taskRepository.save(taskToEdit);
            return ResponseEntity.ok().body(Map.of("success", "Task edited successfully"));
        }catch(TaskNotFound e){
            throw e;
        }catch(Exception e){
            throw new BusinessException(ErrorKey.TASK_EDIT_FAILED, "Error trying to edit task");
        }
    }

    public ResponseEntity<Map<String, String>> deleteTask(UUID taskId, UUID userId){
        Task taskToDelete = getTask(taskId);
        log.info("[LOG] Deleting task => {}", taskToDelete);
        if(!taskToDelete.getUser().getId().equals(userId)){
            throw new BusinessException(ErrorKey.TASK_NOT_OWNED, "The task isn't of the user on context");
        }

        try{
            taskRepository.delete(taskToDelete);
            return ResponseEntity.ok(Map.of("success", "Task deleted Successfully!"));
        }catch(Exception e){
            throw new BusinessException(ErrorKey.TASK_DELETE_FAILED, "Error trying to delete task");
        }
    }

    public Task editTask(Task taskToEdit){
        return taskRepository.save(taskToEdit);
    }
}
