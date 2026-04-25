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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.EditTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class TaskControllerTest extends AbstractIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    @MockitoBean
    private TaskRepository taskRepository;

    @MockitoBean
    private AuthenticatedUser authenticatedUser;

    private User user;
    private UUID userId;
    @BeforeEach
    void setUp() {
        taskRepository.deleteAll();
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    @Test
    void shouldGetAllTasksSuccessfully() throws Exception {
        UUID taskId = UUID.randomUUID();
        TaskResponseDTO responseDTO = new TaskResponseDTO(
                taskId,
                "name",
                "desc",
                "icon",
                1,
                1,
                Map.of(),
                false,
                null,
                null,
                null
        );
        List<TaskResponseDTO> expectedTasks = new ArrayList<>(List.of(responseDTO));
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
        UUID taskId = UUID.randomUUID();
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
        UUID taskId = UUID.randomUUID();
        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok()
            .body(Map.of("success", "Task deleted Successfully!"));

        when(taskService.deleteTask(taskId, userId)).thenReturn(successResponse);

        mockMvc.perform(delete("/task/{taskId}", taskId))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.success").value("Task deleted Successfully!"));
    }

    @Test
    void shouldReturn400WhenCreatingTaskWithEmptyName() throws Exception {
        String json = "{\"name\": \"\", \"description\": \"\", \"iconId\": \"icon1\", \"importance\": 2, \"difficulty\": 2, \"categoriesId\": [], \"oneTimeTask\": false}";

        mockMvc.perform(post("/task")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
               .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
               .andExpect(jsonPath("$.details.name").exists());
    }

    @Test
    void shouldReturn400WhenCreatingTaskWithNameTooShort() throws Exception {
        String json = "{\"name\": \"a\", \"description\": \"\", \"iconId\": \"icon1\", \"importance\": 2, \"difficulty\": 2, \"categoriesId\": [], \"oneTimeTask\": false}";

        mockMvc.perform(post("/task")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
               .andExpect(jsonPath("$.details.name").value("Task needs a minimum of 2 characters"));
    }

    @Test
    void shouldReturn400WhenCreatingTaskWithBlankIcon() throws Exception {
        String json = "{\"name\": \"Task\", \"description\": \"\", \"iconId\": \"   \", \"importance\": 2, \"difficulty\": 2, \"categoriesId\": [], \"oneTimeTask\": false}";

        mockMvc.perform(post("/task")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
               .andExpect(jsonPath("$.details.iconId").exists());
    }

    @Test
    void shouldReturn400WhenCreatingTaskWithNullImportance() throws Exception {
        String json = "{\"name\": \"Task\", \"description\": \"\", \"iconId\": \"icon1\", \"importance\": null, \"difficulty\": 2, \"categoriesId\": [], \"oneTimeTask\": false}";

        mockMvc.perform(post("/task")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
               .andExpect(jsonPath("$.details.importance").exists());
    }

    @Test
    void shouldReturn400WhenCreatingTaskWithDifficultyOutOfRange() throws Exception {
        String json = "{\"name\": \"Task\", \"description\": \"\", \"iconId\": \"icon1\", \"importance\": 2, \"difficulty\": 0, \"categoriesId\": [], \"oneTimeTask\": false}";

        mockMvc.perform(post("/task")
               .contentType(MediaType.APPLICATION_JSON)
               .content(json))
               .andExpect(status().is(HttpServletResponse.SC_BAD_REQUEST))
               .andExpect(jsonPath("$.details.difficulty").exists());
    }
}
