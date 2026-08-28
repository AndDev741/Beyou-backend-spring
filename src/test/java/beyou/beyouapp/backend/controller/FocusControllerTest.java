package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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
import beyou.beyouapp.backend.domain.focus.CycleKind;
import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.FocusCycleResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusDayResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/** HTTP binding of the Focus Mode routes. The rules themselves live in {@code FocusServiceIT}. */
@AutoConfigureMockMvc(addFilters = false)
class FocusControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private FocusService focusService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void recordsACycleWith201() throws Exception {
        UUID item = UUID.randomUUID();
        when(focusService.recordCycle(eq(user), any())).thenReturn(new FocusCycleResponseDTO(
            UUID.randomUUID(), LocalDate.of(2026, 8, 28), item, CycleKind.POMODORO,
            Instant.parse("2026-08-28T10:00:00Z"), Instant.parse("2026-08-28T10:25:00Z"), 25));

        mockMvc.perform(post("/focus/cycles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"itemGroupId":"%s","kind":"POMODORO",
                     "startedAt":"2026-08-28T10:00:00Z","endedAt":"2026-08-28T10:25:00Z","minutes":25}
                    """.formatted(item)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.kind").value("POMODORO"))
            .andExpect(jsonPath("$.minutes").value(25));
    }

    @Test
    void refusesACycleOutsideTheMinuteBounds() throws Exception {
        // The clamp the client applies, restated where it cannot be bypassed: @Max(180) on the DTO.
        mockMvc.perform(post("/focus/cycles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"kind":"POMODORO","startedAt":"2026-08-28T10:00:00Z",
                     "endedAt":"2026-08-28T10:25:00Z","minutes":999}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listsAnItemsMicroTasks() throws Exception {
        UUID item = UUID.randomUUID();
        when(focusService.listMicroTasks(user, item)).thenReturn(List.of(
            new FocusMicroTaskResponseDTO(UUID.randomUUID(), LocalDate.of(2026, 8, 28), item, "Stretch", true, null)));

        mockMvc.perform(get("/focus/micro-tasks").param("itemGroupId", item.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("Stretch"))
            .andExpect(jsonPath("$[0].pinned").value(true));
    }

    @Test
    void addsAMicroTaskWith201_andRefusesABlankName() throws Exception {
        UUID item = UUID.randomUUID();
        when(focusService.addMicroTask(eq(user), any())).thenReturn(
            new FocusMicroTaskResponseDTO(UUID.randomUUID(), LocalDate.of(2026, 8, 28), item, "Water", false, null));

        mockMvc.perform(post("/focus/micro-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemGroupId\":\"%s\",\"name\":\"Water\",\"pinned\":false}".formatted(item)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("Water"));

        mockMvc.perform(post("/focus/micro-tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemGroupId\":\"%s\",\"name\":\"   \",\"pinned\":false}".formatted(item)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void togglesPinsAndDeletes() throws Exception {
        UUID id = UUID.randomUUID();
        FocusMicroTaskResponseDTO dto = new FocusMicroTaskResponseDTO(
            id, LocalDate.of(2026, 8, 28), UUID.randomUUID(), "Water", true, Instant.now());
        when(focusService.toggleMicroTask(user, id)).thenReturn(dto);
        when(focusService.setPinned(user, id, true)).thenReturn(dto);

        mockMvc.perform(patch("/focus/micro-tasks/{id}/toggle", id)).andExpect(status().isOk());
        mockMvc.perform(patch("/focus/micro-tasks/{id}/pin", id).param("pinned", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.pinned").value(true));
        mockMvc.perform(delete("/focus/micro-tasks/{id}", id)).andExpect(status().isNoContent());

        verify(focusService).deleteMicroTask(user, id);
    }

    @Test
    void readsADay() throws Exception {
        when(focusService.getDay(user, LocalDate.of(2026, 8, 28)))
            .thenReturn(new FocusDayResponseDTO(LocalDate.of(2026, 8, 28), List.of(), List.of()));

        mockMvc.perform(get("/focus/day").param("date", "2026-08-28"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value("2026-08-28"));
    }
}
