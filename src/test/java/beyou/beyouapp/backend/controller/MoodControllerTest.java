package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import beyou.beyouapp.backend.domain.mood.MoodService;
import beyou.beyouapp.backend.domain.mood.dto.MoodEntryResponseDTO;
import beyou.beyouapp.backend.domain.mood.dto.SetMoodLevelDTO;
import beyou.beyouapp.backend.domain.mood.dto.UpsertMoodEntryDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/** HTTP binding of the mood routes. The rules themselves live in {@code MoodServiceIT}. */
@AutoConfigureMockMvc(addFilters = false)
class MoodControllerTest extends AbstractIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MoodService moodService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    private static MoodEntryResponseDTO entry(Integer mood, String note) {
        return new MoodEntryResponseDTO(UUID.randomUUID(), DAY, mood, note,
                Instant.parse("2026-09-06T18:00:00Z"));
    }

    @Test
    void readsARangeWithBothBoundsPassedThrough() throws Exception {
        when(moodService.getRange(eq(user), eq(DAY.minusDays(6)), eq(DAY)))
                .thenReturn(List.of(entry(4, "good one")));

        mockMvc.perform(get("/mood").param("from", "2026-08-31").param("to", "2026-09-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2026-09-06"))
                .andExpect(jsonPath("$[0].mood").value(4))
                .andExpect(jsonPath("$[0].note").value("good one"));
    }

    /** Both bounds are optional; the service is what decides the default window. */
    @Test
    void readsARangeWithNoBounds() throws Exception {
        when(moodService.getRange(user, null, null)).thenReturn(List.of());

        mockMvc.perform(get("/mood")).andExpect(status().isOk());

        verify(moodService).getRange(user, null, null);
    }

    /**
     * One day is the range endpoint with both ends the same. There is no single-day route: an
     * unrecorded day is a normal state, and an empty list says so without an error.
     */
    @Test
    void oneDayIsAskedForAsASingleDayRange() throws Exception {
        when(moodService.getRange(user, DAY, DAY)).thenReturn(List.of());

        mockMvc.perform(get("/mood").param("from", "2026-09-06").param("to", "2026-09-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void putReplacesTheWholeEntry() throws Exception {
        when(moodService.upsert(eq(user), eq(DAY), any(UpsertMoodEntryDTO.class)))
                .thenReturn(entry(5, "wrote this"));

        mockMvc.perform(put("/mood/2026-09-06")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mood\":5,\"note\":\"wrote this\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("wrote this"));

        verify(moodService).upsert(eq(user), eq(DAY), eq(new UpsertMoodEntryDTO(5, "wrote this")));
    }

    /**
     * The widget's route. It reaches {@code setLevel}, which has no way to carry a note, so no
     * request shaped like this can clear a journal entry.
     */
    @Test
    void patchSetsTheLevelOnly() throws Exception {
        when(moodService.setLevel(eq(user), eq(DAY), any(SetMoodLevelDTO.class)))
                .thenReturn(entry(3, "written earlier and still here"));

        mockMvc.perform(patch("/mood/2026-09-06")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mood\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("written earlier and still here"));

        verify(moodService).setLevel(eq(user), eq(DAY), eq(new SetMoodLevelDTO(3)));
        verify(moodService, never()).upsert(any(), any(), any());
    }

    /** A note sent to PATCH is not a note the server accepts. It must not reach the service. */
    @Test
    void patchIgnoresAnyNoteInTheBody() throws Exception {
        when(moodService.setLevel(eq(user), eq(DAY), any(SetMoodLevelDTO.class)))
                .thenReturn(entry(3, "the stored one"));

        mockMvc.perform(patch("/mood/2026-09-06")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mood\":3,\"note\":\"should not land\"}"))
                .andExpect(status().isOk());

        verify(moodService).setLevel(eq(user), eq(DAY), eq(new SetMoodLevelDTO(3)));
    }

    @Test
    void aMoodOutsideTheScaleIsRejectedBeforeTheService() throws Exception {
        mockMvc.perform(put("/mood/2026-09-06")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mood\":6}"))
                .andExpect(status().isBadRequest());

        verify(moodService, never()).upsert(any(), any(), any());
    }

    @Test
    void aMoodBelowTheScaleIsRejectedBeforeTheService() throws Exception {
        mockMvc.perform(patch("/mood/2026-09-06")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mood\":0}"))
                .andExpect(status().isBadRequest());

        verify(moodService, never()).setLevel(any(), any(), any());
    }

    @Test
    void aMissingMoodIsRejectedBeforeTheService() throws Exception {
        mockMvc.perform(put("/mood/2026-09-06")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"only text\"}"))
                .andExpect(status().isBadRequest());

        verify(moodService, never()).upsert(any(), any(), any());
    }

    /** The cap is on the API, not on the column, so it has to be enforced at the boundary. */
    @Test
    void aNoteOverTheLimitIsRejectedBeforeTheService() throws Exception {
        String tooLong = "a".repeat(4001);

        mockMvc.perform(put("/mood/2026-09-06")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mood\":3,\"note\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());

        verify(moodService, never()).upsert(any(), any(), any());
    }

    @Test
    void deleteAnswers204() throws Exception {
        mockMvc.perform(delete("/mood/2026-09-06")).andExpect(status().isNoContent());

        verify(moodService).delete(user, DAY);
    }
}
