package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.calendar.CalendarService;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarDayDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarGoalDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarMoodDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarOverviewDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineSource;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskCompletionDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskRequestDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/**
 * HTTP binding of the calendar routes: paths, status codes, and the JSON shapes the two clients
 * were built against. The rules themselves are in the calendar integration tests.
 */
@AutoConfigureMockMvc(addFilters = false)
class CalendarControllerTest extends AbstractIntegrationTest {

    private static final UUID TASK_ID = UUID.fromString("7d9b5c1e-0f2a-4c3b-9a8d-1e2f3a4b5c6d");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CalendarService calendarService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    private static CalendarTaskResponseDTO task(boolean completed) {
        return new CalendarTaskResponseDTO(TASK_ID, "Sign the lease", null, LocalDate.of(2026, 10, 14),
                LocalTime.of(10, 0), 60, completed, completed ? Instant.parse("2026-10-14T13:20:00Z") : null,
                completed ? 10.0 : 0.0, Instant.parse("2026-10-14T12:00:00Z"));
    }

    @Test
    void createAnswers201WithTheContractShape() throws Exception {
        when(calendarService.create(eq(user), any(CalendarTaskRequestDTO.class))).thenReturn(task(false));

        mockMvc.perform(post("/calendar/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Sign the lease","note":null,"date":"2026-10-14","time":"10:00","advanceMinutes":60}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(TASK_ID.toString()))
                .andExpect(jsonPath("$.title").value("Sign the lease"))
                .andExpect(jsonPath("$.date").value("2026-10-14"))
                .andExpect(jsonPath("$.time").value("10:00"))
                .andExpect(jsonPath("$.advanceMinutes").value(60))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.completedAt").isEmpty())
                .andExpect(jsonPath("$.xpAwarded").value(0.0))
                .andExpect(jsonPath("$.notifyAt").value("2026-10-14T12:00:00Z"));

        verify(calendarService).create(user, new CalendarTaskRequestDTO(
                "Sign the lease", null, LocalDate.of(2026, 10, 14), LocalTime.of(10, 0), 60));
    }

    @Test
    void onlyTitleAndDateAreRequired() throws Exception {
        when(calendarService.create(eq(user), any(CalendarTaskRequestDTO.class))).thenReturn(task(false));

        mockMvc.perform(post("/calendar/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Book the dentist\",\"date\":\"2026-10-16\"}"))
                .andExpect(status().isCreated());

        verify(calendarService).create(user, new CalendarTaskRequestDTO(
                "Book the dentist", null, LocalDate.of(2026, 10, 16), null, null));
    }

    @Test
    void invalidBodiesAreRefusedBeforeTheService() throws Exception {
        for (String body : List.of(
                "{\"title\":\"x\",\"date\":\"2026-10-14\"}",
                "{\"title\":\"   \",\"date\":\"2026-10-14\"}",
                "{\"title\":\"Sign the lease\"}",
                "{\"title\":\"Sign the lease\",\"date\":\"2026-10-14\",\"advanceMinutes\":-5}",
                "{\"title\":\"Sign the lease\",\"date\":\"2026-10-14\",\"advanceMinutes\":10081}",
                "{\"title\":\"Sign the lease\",\"date\":\"2026-10-14\",\"note\":\"" + "n".repeat(1001) + "\"}")) {
            mockMvc.perform(post("/calendar/tasks").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
        }
        verify(calendarService, never()).create(any(), any());
    }

    @Test
    void updateReplacesTheTask() throws Exception {
        when(calendarService.update(eq(user), eq(TASK_ID), any(CalendarTaskRequestDTO.class))).thenReturn(task(false));

        mockMvc.perform(put("/calendar/tasks/" + TASK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Sign the lease\",\"date\":\"2026-10-15\",\"time\":\"15:00\",\"advanceMinutes\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(TASK_ID.toString()));

        verify(calendarService).update(user, TASK_ID, new CalendarTaskRequestDTO(
                "Sign the lease", null, LocalDate.of(2026, 10, 15), LocalTime.of(15, 0), 0));
    }

    @Test
    void deleteAnswers204() throws Exception {
        mockMvc.perform(delete("/calendar/tasks/" + TASK_ID)).andExpect(status().isNoContent());

        verify(calendarService).delete(user, TASK_ID);
    }

    @Test
    void completeAnswersTheTaskAndTheRefresh() throws Exception {
        RefreshUiDTO refresh = new RefreshUiDTO(new RefreshUserDTO(3, false, 9, 110.0, 2, 100.0, 150.0),
                List.of(), null, null);
        when(calendarService.toggleComplete(user, TASK_ID)).thenReturn(new CalendarTaskCompletionDTO(task(true), refresh));

        mockMvc.perform(put("/calendar/tasks/" + TASK_ID + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completed").value(true))
                .andExpect(jsonPath("$.task.completedAt").value("2026-10-14T13:20:00Z"))
                .andExpect(jsonPath("$.task.xpAwarded").value(10.0))
                .andExpect(jsonPath("$.refreshUi.refreshUser.xp").value(110.0))
                .andExpect(jsonPath("$.refreshUi.refreshCategories").isEmpty());
    }

    @Test
    void ackAnswers204() throws Exception {
        mockMvc.perform(post("/calendar/tasks/" + TASK_ID + "/ack")).andExpect(status().isNoContent());

        verify(calendarService).acknowledge(user, TASK_ID);
    }

    @Test
    void upcomingIsAPlainList() throws Exception {
        when(calendarService.upcoming(user)).thenReturn(List.of(task(false)));

        mockMvc.perform(get("/calendar/tasks/upcoming"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(TASK_ID.toString()))
                .andExpect(jsonPath("$[0].notifyAt").value("2026-10-14T12:00:00Z"));
    }

    /** Someone else's task: the service's not-found is a 404 with its own key, on every route. */
    @Test
    void notFoundIs404() throws Exception {
        BusinessException notFound = new BusinessException(ErrorKey.CALENDAR_TASK_NOT_FOUND, "Calendar task not found");
        when(calendarService.toggleComplete(user, TASK_ID)).thenThrow(notFound);
        when(calendarService.update(eq(user), eq(TASK_ID), any())).thenThrow(notFound);
        doThrow(notFound).when(calendarService).delete(user, TASK_ID);
        doThrow(notFound).when(calendarService).acknowledge(user, TASK_ID);

        mockMvc.perform(put("/calendar/tasks/" + TASK_ID + "/complete"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorKey").value("CALENDAR_TASK_NOT_FOUND"));
        mockMvc.perform(put("/calendar/tasks/" + TASK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Mine now\",\"date\":\"2026-10-15\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/calendar/tasks/" + TASK_ID)).andExpect(status().isNotFound());
        mockMvc.perform(post("/calendar/tasks/" + TASK_ID + "/ack")).andExpect(status().isNotFound());
    }

    @Test
    void overviewPassesTheRangeAndRendersTheDay() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 13);
        UUID routineId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        when(calendarService.getOverview(user, day, day)).thenReturn(new CalendarOverviewDTO(day, day,
                LocalDate.of(2026, 10, 14), List.of(new CalendarDayDTO(day,
                        List.of(task(false)),
                        List.of(new CalendarRoutineDTO(routineId, "Morning", "sun", CalendarRoutineSource.SNAPSHOT, 9, 7, false)),
                        List.of(new CalendarGoalDTO(goalId, "Read 12 books", "book", false)),
                        new CalendarMoodDTO(4, true)))));

        mockMvc.perform(get("/calendar").param("from", "2026-10-13").param("to", "2026-10-13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-13"))
                .andExpect(jsonPath("$.today").value("2026-10-14"))
                .andExpect(jsonPath("$.days[0].date").value("2026-10-13"))
                .andExpect(jsonPath("$.days[0].tasks[0].time").value("10:00"))
                .andExpect(jsonPath("$.days[0].routines[0].routineId").value(routineId.toString()))
                .andExpect(jsonPath("$.days[0].routines[0].source").value("SNAPSHOT"))
                .andExpect(jsonPath("$.days[0].routines[0].totalItems").value(9))
                .andExpect(jsonPath("$.days[0].routines[0].checkedItems").value(7))
                .andExpect(jsonPath("$.days[0].routines[0].completed").value(false))
                .andExpect(jsonPath("$.days[0].goals[0].goalId").value(goalId.toString()))
                .andExpect(jsonPath("$.days[0].goals[0].complete").value(false))
                .andExpect(jsonPath("$.days[0].mood.level").value(4))
                .andExpect(jsonPath("$.days[0].mood.hasNote").value(true));
    }

    @Test
    void invalidRangeIs400WithItsKey() throws Exception {
        when(calendarService.getOverview(eq(user), any(), any()))
                .thenThrow(new BusinessException(ErrorKey.CALENDAR_RANGE_INVALID, "'to' is before 'from'"));

        mockMvc.perform(get("/calendar").param("from", "2026-10-14").param("to", "2026-10-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("CALENDAR_RANGE_INVALID"));
    }

    /** A missing bound reaches the service, which answers with the calendar's own key. */
    @Test
    void missingBoundsReachTheService() throws Exception {
        when(calendarService.getOverview(user, null, null))
                .thenThrow(new BusinessException(ErrorKey.CALENDAR_RANGE_INVALID, "Both 'from' and 'to' are required"));

        mockMvc.perform(get("/calendar"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("CALENDAR_RANGE_INVALID"));
    }
}
