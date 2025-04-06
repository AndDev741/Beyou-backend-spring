package beyou.beyouapp.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import beyou.beyouapp.backend.controllers.TaskController;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@ExtendWith(MockitoExtension.class)
public class TaskControllerTest {

    @Mock
    private TaskService taskService;

    @Mock
    private AuthenticatedUser authenticatedUser;

    @InjectMocks
    private TaskController taskController;

    private UUID userId;
    private UUID taskId;
    private User user;
    private Task task;

    @BeforeEach
    public void setUp() {
        userId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        
        task = new Task();
        task.setId(taskId);
        task.setUser(user);
    }

    @Test
    public void shouldGetAllTasksSuccessfully() {
        // Arrange
        List<Task> expectedTasks = new ArrayList<>();
        expectedTasks.add(task);
        
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(taskService.getAllTasks(userId)).thenReturn(expectedTasks);

        // Act
        List<Task> actualTasks = taskController.getTasks();

        // Assert
        assertEquals(expectedTasks, actualTasks);
    }

    @Test
    public void shouldCreateTaskSuccessfully() {
        // Arrange
        CreateTaskRequestDTO requestDTO = new CreateTaskRequestDTO(
            "Test Task", 
            "Description", 
            "icon1", 
            1, 
            1, 
            null
        );
        
        ResponseEntity<Map<String, String>> expectedResponse = ResponseEntity.ok()
            .body(Map.of("success", "Task created Successfully"));
        
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(taskService.createTask(requestDTO, userId)).thenReturn(expectedResponse);

        // Act
        ResponseEntity<Map<String, String>> actualResponse = taskController.createTask(requestDTO);

        // Assert
        assertEquals(expectedResponse, actualResponse);
    }

    @Test
    public void shouldEditTaskSuccessfully() {
        // Arrange
        EditTaskRequestDTO requestDTO = new EditTaskRequestDTO(
            taskId, 
            "Updated Task", 
            "Updated Description", 
            "icon2", 
            2, 
            2, 
            null
        );
        
        ResponseEntity<Map<String, String>> expectedResponse = ResponseEntity.ok()
            .body(Map.of("success", "Task edited successfully"));
        
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(taskService.editTask(requestDTO, userId)).thenReturn(expectedResponse);

        // Act
        ResponseEntity<Map<String, String>> actualResponse = taskController.editTask(requestDTO);

        // Assert
        assertEquals(expectedResponse, actualResponse);
    }

    @Test
    public void shouldDeleteTaskSuccessfully() {
        // Arrange
        ResponseEntity<Map<String, String>> expectedResponse = ResponseEntity.ok()
            .body(Map.of("success", "Task deleted Successfully!"));
        
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        when(taskService.deleteTask(taskId, userId)).thenReturn(expectedResponse);

        // Act
        ResponseEntity<Map<String, String>> actualResponse = taskController.deleteTask(taskId);

        // Assert
        assertEquals(expectedResponse, actualResponse);
    }
}
