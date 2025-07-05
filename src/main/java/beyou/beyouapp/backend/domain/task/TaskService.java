package beyou.beyouapp.backend.domain.task;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.task.TaskNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TaskService {
    TaskRepository taskRepository;
    UserRepository userRepository;
    CategoryService categoryService;
    DiaryRoutineRepository diaryRoutineRepository;

    public TaskService(TaskRepository taskRepository, UserRepository userRepository, CategoryService categoryService, DiaryRoutineRepository diaryRoutineRepository){
        this.taskRepository = taskRepository;
        this.userRepository = userRepository;
        this.categoryService = categoryService;
        this.diaryRoutineRepository = diaryRoutineRepository;
    }

    public Task getTask(UUID taskId){
        return taskRepository.findById(taskId).orElseThrow(() -> new TaskNotFound("Task not found"));
    }

    public List<Task> getAllTasks(UUID userId){
        List<Task> tasks = taskRepository.findAllByUserId(userId).orElseThrow(() -> new UserNotFound("User not found when tried to get tasks"));
        deleteAllMarked(tasks, userId);
        return taskRepository.findAllByUserId(userId).orElseThrow(() -> new UserNotFound("User not found when tried to get tasks"));
    }

    private void deleteAllMarked (List<Task> tasks, UUID userId) {
        
        List<UUID> deletedTasks = new ArrayList<>();
        tasks.forEach(task -> {
            if(task.getMarkedToDelete() != null && task.getMarkedToDelete().isBefore(LocalDate.now())){
               taskRepository.delete(task);
               deletedTasks.add(task.getId());
            }
        });

        if(!deletedTasks.isEmpty()){
            List<DiaryRoutine> diaryRoutines = diaryRoutineRepository.findAllByUserId(userId);
            diaryRoutines.forEach(diaryRoutine -> {
                diaryRoutine.getRoutineSections().forEach(section -> {
                    section.getTaskGroups().removeIf(group -> deletedTasks.contains(group.getTask().getId()));
                });
            });
        }
        
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
        
        Task taskToCreate = new Task(createTaskDTO, Optional.of(categoriesToAdd), user);

        try{
            taskRepository.save(taskToCreate);
            return ResponseEntity.ok().body(Map.of("success", "Task created Successfully"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "Error tying to create the task"));
        }
    }

    public ResponseEntity<Map<String, String>> editTask(EditTaskRequestDTO editTaskRequestDTO, UUID userId){
        Task taskToEdit = getTask(editTaskRequestDTO.taskId());

        if(!taskToEdit.getUser().getId().equals(userId)){
            throw new TaskNotFound("The task isn't of the user on context");
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
        taskToEdit.setCategories(categoriesToAdd);
        taskToEdit.setOneTimeTask(editTaskRequestDTO.oneTimeTask());

        try{
            taskRepository.save(taskToEdit);
            return ResponseEntity.ok().body(Map.of("success", "Task edited successfully"));
        }catch(TaskNotFound e){
            throw e;
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to edit task"));
        }
    }

    public ResponseEntity<Map<String, String>> deleteTask(UUID taskId, UUID userId){
        Task taskToDelete = getTask(taskId);
        log.info("[LOG] Deleting task => ", taskToDelete);
        if(!taskToDelete.getUser().getId().equals(userId)){
            throw new TaskNotFound("The task isn't of the user on context");
        }

        try{
            taskRepository.delete(taskToDelete);
            return ResponseEntity.ok(Map.of("success", "Task deleted Successfully!"));
        }catch(Exception e){
            return ResponseEntity.badRequest().body(Map.of("error", "Error trying to delete task"));
        }
    }

    public Task editTask(Task taskToEdit){
        return taskRepository.save(taskToEdit);
    }
}
