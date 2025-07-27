package beyou.beyouapp.backend.controller;

import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import jakarta.transaction.Transactional;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
@Transactional
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
public class GoalControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GoalService goalService;

    @MockBean
    private AuthenticatedUser authenticatedUser;

    private User user;
    private UUID userId;
private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void shouldGetGoalsSuccessfully() throws Exception {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        when(goalService.getAllGoals(userId)).thenReturn(List.of(goal));

        mockMvc.perform(get("/goal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(goal.getId().toString()));
    }

    @Test
    void shouldCreateGoalSuccessfully() throws Exception {
        CreateGoalRequestDTO dto = new CreateGoalRequestDTO(
                "Name", "icon", "desc", 100.0, "unit", 0.0,
                List.of(UUID.randomUUID()), "motivation",
                LocalDate.now(), LocalDate.now().plusDays(1),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok(Map.of("success", "Goal created"));
        when(goalService.createGoal(dto, userId)).thenReturn(response);

        mockMvc.perform(post("/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Goal created"));
    }

    @Test
    void shouldEditGoalSuccessfully() throws Exception {
        EditGoalRequestDTO dto = new EditGoalRequestDTO(
                UUID.randomUUID(), "NewName", "icon", "desc", 200.0, "unit", 10.0,
                false, List.of(UUID.randomUUID()), "motivation",
                LocalDate.now(), LocalDate.now().plusDays(2),
                GoalStatus.IN_PROGRESS, GoalTerm.MEDIUM_TERM);
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok(Map.of("success", "Goal edited"));
        when(goalService.editGoal(dto, userId)).thenReturn(response);

        mockMvc.perform(put("/goal")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Goal edited"));
    }

    @Test
    void shouldDeleteGoalSuccessfully() throws Exception {
        UUID goalId = UUID.randomUUID();
        ResponseEntity<Map<String, String>> response = ResponseEntity.ok(Map.of("success", "Goal deleted"));
        when(goalService.deleteGoal(goalId, userId)).thenReturn(response);

        mockMvc.perform(delete("/goal/{id}", goalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value("Goal deleted"));
    }
}
