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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.user.User;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class HabitControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HabitService habitService;

    @MockBean
    private HabitRepository repository;

    User user = new User();
    UUID userID = UUID.randomUUID();
    Habit habit = new Habit();
    UUID habitID = UUID.randomUUID();

    @BeforeEach
    private void setup() {
        repository.deleteAll();

        user.setId(userID);
        habit.setId(habitID);
        habit.setUser(user);

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void shouldGetHabitsSuccessfully() throws Exception{
        when(habitService.getHabits(userID)).thenReturn(new ArrayList<>(List.of(habit)));

        mockMvc.perform(get("/habit"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldCreateAHabitSuccessfully() throws JsonProcessingException, Exception{
        List<UUID> categories = new ArrayList<>(List.of(UUID.randomUUID()));

        CreateHabitDTO createHabitDTO = new CreateHabitDTO( 
        "name", "", "", "", 2, 2, categories, 0, 0);

        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));

        when(habitService.createHabit(createHabitDTO, userID)).thenReturn(successResponse);

        mockMvc.perform(post("/habit")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(createHabitDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").exists());

    }

    @Test
    void shouldEditAHabitSuccessfully() throws JsonProcessingException, Exception {
        List<UUID> categories = new ArrayList<>(List.of(UUID.randomUUID()));
        EditHabitDTO editHabitDTO = new EditHabitDTO(
            habitID, "name", "", "", "", 2, 2, categories
        );

        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok().body(Map.of("success", "Habit edited successfully"));

        when(habitService.editHabit(editHabitDTO, userID)).thenReturn(successResponse);

        mockMvc.perform(put("/habit")
        .contentType(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(editHabitDTO)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").exists());
    }

    @Test
    void shouldDeleteAHabitSUccessfully() throws Exception {
        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok().body(Map.of("success", "Habit deleted successfully"));

        when(habitService.deleteHabit(habitID, userID)).thenReturn(successResponse);

        mockMvc.perform(delete("/habit/{habitId}", habitID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value("Habit deleted successfully"));
    }

    //Exceptions

    
}
