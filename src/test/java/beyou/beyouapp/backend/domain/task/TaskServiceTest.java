package beyou.beyouapp.backend.domain.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.task.TaskNotFound;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class TaskServiceTest {
    @Mock
    TaskRepository taskRepository;

    @Mock
    CategoryService categoryService;

    @Mock
    UserRepository userRepository;

    @Mock
    DiaryRoutineRepository diaryRoutineRepository;

    @InjectMocks
    TaskService taskService;

    UUID taskId = UUID.randomUUID();
    Task newTask = new Task();
    List<Task> tasks = new ArrayList<Task>(List.of(newTask));
    UUID userId = UUID.randomUUID();
    User user = new User();

    @Test
    public void shouldGetTaskSuccessfully(){
        newTask.setId(taskId);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(newTask));

        Task task = taskService.getTask(taskId);

        assertEquals(taskId, task.getId());
    }

    @Test
    public void shoulGetAllTheTasksFromUser(){
        user.setId(userId);

        when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.of(tasks));

        List<Task> getTasks = taskService.getAllTasks(userId);

        assertEquals(tasks.get(0), getTasks.get(0));
    }

    @Test
    public void shouldCreateATaskSuccessfully(){
        List<UUID> categoriesId= new ArrayList<>(List.of(UUID.randomUUID(), UUID.randomUUID()));
        Category category = new Category();
        category.setId(categoriesId.get(0));

        CreateTaskRequestDTO createTaskDTO = new CreateTaskRequestDTO(
        "taskName",
        "Task description", 
        "IconId",
        2, 
        2,
        categoriesId,
        false);

        ResponseEntity<Map<String, String>> successMessage = ResponseEntity.ok().body(Map.of("success", "Task created Successfully"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(categoryService.getCategory(categoriesId.get(0))).thenReturn(category);
        
        ResponseEntity<Map<String, String>> responseMessage = taskService.createTask(createTaskDTO, userId);

        assertEquals(successMessage, responseMessage);
    }

    @Test
    public void shouldCreateACategoryWithoutTheOptionalAttributes(){
        CreateTaskRequestDTO createTaskDTO = new CreateTaskRequestDTO(
        "taskName",
        "Task description", 
        "IconId",
        null,
        null,
        null,
        false);

        ResponseEntity<Map<String, String>> successMessage = ResponseEntity.ok().body(Map.of("success", "Task created Successfully"));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        
        ResponseEntity<Map<String, String>> responseMessage = taskService.createTask(createTaskDTO, userId);

        assertEquals(successMessage, responseMessage);
    }

    @Test
    public void shouldEditATaskSuccessfully(){
        user.setId(userId);
        UUID taskId = UUID.randomUUID();
        Task taskToEdit = new Task();
        taskToEdit.setId(taskId);
        taskToEdit.setUser(user);
        taskToEdit.setName("oldName");

        EditTaskRequestDTO editTaskRequestDTO = new EditTaskRequestDTO(taskId, "newName", null, null, null, null, null, false);
        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok().body(Map.of("success", "Task edited successfully"));

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskToEdit));

        ResponseEntity<Map<String, String>> editTaskResponse = taskService.editTask(editTaskRequestDTO, userId);
        
        assertEquals(successResponse, editTaskResponse);
    }

    @Test
    public void shouldDeleteSuccessfullyATask(){
        user.setId(userId);
        Task taskToDelete = new Task();
        taskToDelete.setId(taskId);
        taskToDelete.setUser(user);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(taskToDelete));

        ResponseEntity<Map<String, String>> deleteTaskResponse = taskService.deleteTask(taskId, userId);

        assertEquals(ResponseEntity.ok(Map.of("success", "Task deleted Successfully!")), deleteTaskResponse);
    }

    @Test
    public void shouldDelete1DayAfterTheMarkedToDeleteDate(){
        user.setId(userId);
        Task taskToDelete = new Task();
        taskToDelete.setId(taskId);
        taskToDelete.setUser(user);
        taskToDelete.setOneTimeTask(true);
        taskToDelete.setMarkedToDelete(LocalDate.now().minusDays(1));

        when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.of(List.of(taskToDelete)));
        when(taskRepository.findAllByUserId(userId))
            .thenReturn(Optional.of(List.of(taskToDelete)))  // 1ª call
            .thenReturn(Optional.of(new ArrayList<>()));    // 2ª call

        List<Task> result = taskService.getAllTasks(userId);

        verify(taskRepository, times(2)).findAllByUserId(userId);
        verify(taskRepository, times(1)).deleteAll(List.of(taskToDelete));
        assertEquals(new ArrayList<>(), result);
    }

    //Exceptions

     @Test
    public void shouldThrowExceptionWhenTaskNotFound() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThrows(TaskNotFound.class, () -> taskService.getTask(taskId));
    }

    @Test
    public void shouldThrowExceptionWhenUserNotFoundGettingTasks() {
        when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.empty());
        assertThrows(UserNotFound.class, () -> taskService.getAllTasks(userId));
    }

    @Test
    public void shouldThrowExceptionWhenUserNotFoundCreatingTask() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        CreateTaskRequestDTO createTaskDTO = new CreateTaskRequestDTO("task", "desc", "icon", 1, 1, null, false);
        assertThrows(UserNotFound.class, () -> taskService.createTask(createTaskDTO, userId));
    }

    @Test
    public void shouldThrowExceptionWhenEditingTaskThatDoesNotExist() {
        EditTaskRequestDTO editTaskRequestDTO = new EditTaskRequestDTO(taskId, "newName", null, null, null, null, null, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThrows(TaskNotFound.class, () -> taskService.editTask(editTaskRequestDTO, userId));
    }

    @Test
    public void shouldThrowExceptionWhenEditingTaskOfAnotherUser() {
        UUID anotherUserId = UUID.randomUUID();
        User user = new User();
        user.setId(anotherUserId);
        Task task = new Task();
        task.setId(taskId);
        task.setUser(user);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        EditTaskRequestDTO editTaskRequestDTO = new EditTaskRequestDTO(taskId, "newName", null, null, null, null, null, false);
        
        assertThrows(TaskNotFound.class, () -> taskService.editTask(editTaskRequestDTO, userId));
    }

    @Test
    public void shouldThrowExceptionWhenDeletingTaskThatDoesNotExist() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());
        assertThrows(TaskNotFound.class, () -> taskService.deleteTask(taskId, userId));
    }

    @Test
    public void shouldThrowExceptionWhenDeletingTaskOfAnotherUser() {
        UUID anotherUserId = UUID.randomUUID();
        User user = new User();
        user.setId(anotherUserId);
        Task task = new Task();
        task.setId(taskId);
        task.setUser(user);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        
        assertThrows(TaskNotFound.class, () -> taskService.deleteTask(taskId, userId));
    }
}
