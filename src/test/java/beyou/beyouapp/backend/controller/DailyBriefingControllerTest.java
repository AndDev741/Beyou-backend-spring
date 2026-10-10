package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.briefing.DailyBriefingService;
import beyou.beyouapp.backend.domain.briefing.NarrativeStatus;
import beyou.beyouapp.backend.domain.briefing.dto.BriefingNarrative;
import beyou.beyouapp.backend.domain.briefing.dto.DailyBriefingResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/** HTTP binding of the briefing routes. The facts and the prose live in {@code DailyBriefingServiceIT}. */
@AutoConfigureMockMvc(addFilters = false)
class DailyBriefingControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DailyBriefingService dailyBriefingService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
    }

    @Test
    void theBriefingIsTheCallersOwn() throws Exception {
        when(dailyBriefingService.briefingFor(user)).thenReturn(new DailyBriefingResponseDTO(
                LocalDate.of(2026, 10, 9), null, null, BriefingNarrative.absent(NarrativeStatus.PENDING), null));

        mockMvc.perform(get("/daily-briefing"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-10-09"))
                .andExpect(jsonPath("$.narrative.status").value("PENDING"));
    }

    /**
     * The poll a client runs while the prose is PENDING. It must never reach briefingFor, which
     * recomputes the facts and spends the briefing allowance.
     */
    @Test
    void theNarrativePollReadsTheProseAndNothingElse() throws Exception {
        when(dailyBriefingService.narrativeFor(user)).thenReturn(new BriefingNarrative(
                NarrativeStatus.READY, List.of("Two habits are waiting."), List.of("Yesterday went well.")));

        mockMvc.perform(get("/daily-briefing/narrative"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.todayLines[0]").value("Two habits are waiting."));

        verify(dailyBriefingService, never()).briefingFor(user);
    }

    @Test
    void seenAnswers204() throws Exception {
        mockMvc.perform(post("/daily-briefing/seen")).andExpect(status().isNoContent());

        verify(dailyBriefingService).markSeen(user);
    }
}
