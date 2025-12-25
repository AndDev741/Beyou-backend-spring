package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.UpdateScheduleDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
public class ScheduleControllerTest {
    
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ScheduleService scheduleService;

    @MockBean
    private AuthenticatedUser authenticatedUser;

    Schedule schedule;
    User user = new User();
    UUID userID = UUID.randomUUID();
    UUID scheduleId = UUID.randomUUID();

    @BeforeEach
    private void setup() {
        user.setId(userID);
        Set<WeekDay> weekDays = new HashSet<>();
        weekDays.add(WeekDay.Monday);
        schedule = new Schedule(
            scheduleId,
            weekDays
        );

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities())
        );
    }

    @Test
    void shouldGetAllSchedulesSuccessfully() throws Exception {
        List<Schedule> schedules = List.of(schedule);

        when(scheduleService.findAll(userID))
            .thenReturn(schedules);

        mockMvc.perform(get("/schedule"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(scheduleId.toString()))
            .andExpect(jsonPath("$[0].days", hasItem(WeekDay.Monday.name())));
    }

    @Test
    void shouldCreateScheduleSuccessfully() throws Exception {
        CreateScheduleDTO dto = new CreateScheduleDTO(Set.of(WeekDay.Monday), UUID.randomUUID());

        when(scheduleService.create(dto, userID)).thenReturn(schedule);

        mockMvc.perform(post("/schedule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(scheduleId.toString()))
            .andExpect(jsonPath("$.days", hasItem(WeekDay.Monday.name())));

        verify(scheduleService).create(dto, userID);
    }

    @Test
    void shouldReturnBadRequestWhenCreateScheduleWithoutDays() throws Exception {
        CreateScheduleDTO invalidDto = new CreateScheduleDTO(Set.of(), UUID.randomUUID());

        mockMvc.perform(post("/schedule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(invalidDto)))
            .andExpect(status().isBadRequest());

        verify(scheduleService, never()).create(any(CreateScheduleDTO.class), any(UUID.class));
    }

    @Test
    void shouldUpdateScheduleSuccessfully() throws Exception {
        UpdateScheduleDTO dto = new UpdateScheduleDTO(scheduleId, Set.of(WeekDay.Friday), UUID.randomUUID());
        Schedule updated = new Schedule(scheduleId, dto.days());

        when(scheduleService.update(dto, userID)).thenReturn(updated);

        mockMvc.perform(put("/schedule")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(scheduleId.toString()))
            .andExpect(jsonPath("$.days", hasItem(WeekDay.Friday.name())));

        verify(scheduleService).update(dto, userID);
    }

    @Test
    void shouldDeleteScheduleSuccessfully() throws Exception {
        mockMvc.perform(delete("/schedule/{scheduleId}", scheduleId))
            .andExpect(status().isNoContent());

        verify(scheduleService).delete(scheduleId, userID);
    }
}
