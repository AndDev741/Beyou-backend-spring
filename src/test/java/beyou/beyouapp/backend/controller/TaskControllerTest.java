package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.user.User;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class TaskControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TaskService taskService;

    @MockBean
    private TaskRepository taskRepository;

    private User user;
    private UUID userId;
    private Task task;
    private UUID taskId;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAll();
        userId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        user = new User();
        user.setId(userId);

        task = new Task();
        task.setId(taskId);
        task.setUser(user);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    @Test
    void shouldGetAllTasksSuccessfully() throws Exception {
        List<Task> expectedTasks = new ArrayList<>(List.of(task));
        when(taskService.getAllTasks(userId)).thenReturn(expectedTasks);

        mockMvc.perform(get("/task"))
               .andExpect(status().isOk());
    }

    @Test
    void shouldCreateTaskSuccessfully() throws Exception {
        CreateTaskRequestDTO dto = new CreateTaskRequestDTO(
            "Test Task",
            "Description",
            "icon1",
            1,
            1,
            null,
            false
        );
        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok()
            .body(Map.of("success", "Task created Successfully"));

        when(taskService.createTask(dto, userId)).thenReturn(successResponse);

        mockMvc.perform(post("/task")
               .contentType(MediaType.APPLICATION_JSON)
               .content(new ObjectMapper().writeValueAsString(dto)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value("Task created Successfully"));
    }

    @Test
    void shouldEditTaskSuccessfully() throws Exception {
        EditTaskRequestDTO dto = new EditTaskRequestDTO(
            taskId,
            "Updated Task",
            "Updated Description",
            "icon2",
            2,
            2,
            null,
            false
        );
        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok()
            .body(Map.of("success", "Task edited successfully"));

        when(taskService.editTask(dto, userId)).thenReturn(successResponse);

        mockMvc.perform(put("/task")
               .contentType(MediaType.APPLICATION_JSON)
               .content(new ObjectMapper().writeValueAsString(dto)))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value("Task edited successfully"));
    }

    @Test
    void shouldDeleteTaskSuccessfully() throws Exception {
        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok()
            .body(Map.of("success", "Task deleted Successfully!"));

        when(taskService.deleteTask(taskId, userId)).thenReturn(successResponse);

        mockMvc.perform(delete("/task/{taskId}", taskId))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value("Task deleted Successfully!"));
    }
}
