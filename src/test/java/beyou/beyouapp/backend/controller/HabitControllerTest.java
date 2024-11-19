package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class HabitControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HabitService habitService;

    @Test
    void shouldGetHabitsSuccessfully() throws Exception{
        UUID userId = UUID.randomUUID();

        ArrayList<Habit> habits = new ArrayList<>(List.of(new Habit()));

        when(habitService.getHabits(userId)).thenReturn(new ArrayList<>(habits));

        mockMvc.perform(get("/habit/{userId}", userId))
                .andExpect(status().isOk());
    }

    @Test
    void shouldCreateAHabitSuccessfully() throws JsonProcessingException, Exception{
        UUID userId = UUID.randomUUID();
        List<UUID> categories = new ArrayList<>(List.of(UUID.randomUUID()));

        CreateHabitDTO createHabitDTO = new CreateHabitDTO(userId, 
        "name", "", "", "", 2, 2, 
        categories, 0, 0);

        ResponseEntity<Map<String, String>> successResponse = ResponseEntity.ok().body(Map.of("success", "Habit saved successfully"));

        when(habitService.createHabit(any(CreateHabitDTO.class))).thenReturn(successResponse);

        mockMvc.perform(post("/habit")
        .accept(MediaType.APPLICATION_JSON)
        .content(new ObjectMapper().writeValueAsString(createHabitDTO)))
        .andExpect(status().isOk());
    }
}
