package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckHistoryService;
import beyou.beyouapp.backend.domain.checkday.dto.CheckDayResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

/**
 * The HTTP binding of {@code GET /check-history} (R9, KTD23) — what the query string is
 * allowed to say, and what comes back when it says something wrong. The range arithmetic
 * itself is pinned in {@code CheckHistoryServiceUnitTest}; here the service is a mock, in
 * the shape every other controller test in this package uses.
 */
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
class CheckHistoryControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckHistoryService checkHistoryService;

    @MockitoBean
    private AuthenticatedUser authenticatedUser;

    private final User user = new User();
    private final UUID userId = UUID.randomUUID();
    private final UUID habitId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        user.setId(userId);
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void returnsOneEntryPerDayInOrderWithAbsentDaysMarkedUnknown() throws Exception {
        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 3);
        when(checkHistoryService.history(eq(user), eq(CheckDayOwnerType.HABIT), eq(habitId),
                eq(from), eq(to)))
                .thenReturn(new CheckDayResponseDTO(CheckDayOwnerType.HABIT, habitId, from, to,
                        List.of(
                                day(from, CheckDayResponseDTO.Outcome.DONE),
                                day(from.plusDays(1), CheckDayResponseDTO.Outcome.UNKNOWN),
                                day(to, CheckDayResponseDTO.Outcome.SKIPPED))));

        mockMvc.perform(get("/check-history")
                        .param("ownerType", "HABIT")
                        .param("ownerId", habitId.toString())
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerType").value("HABIT"))
                .andExpect(jsonPath("$.ownerId").value(habitId.toString()))
                .andExpect(jsonPath("$.from").value("2026-03-01"))
                .andExpect(jsonPath("$.to").value("2026-03-03"))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.days[0].day").value("2026-03-01"))
                .andExpect(jsonPath("$.days[0].outcome").value("DONE"))
                // R18 — the gap is present and named, not dropped.
                .andExpect(jsonPath("$.days[1].day").value("2026-03-02"))
                .andExpect(jsonPath("$.days[1].outcome").value("UNKNOWN"))
                .andExpect(jsonPath("$.days[2].outcome").value("SKIPPED"));
    }

    @Test
    void omittingFromAndToPassesBothThroughAsNullSoTheServiceCanDefaultThem() throws Exception {
        // The default window is the service's decision, not the binder's — a controller-side
        // default would resolve "today" in the server zone and hand a west-of-server user
        // tomorrow (R15).
        LocalDate to = LocalDate.of(2026, 5, 20);
        LocalDate from = to.minusDays(27);
        when(checkHistoryService.history(any(), any(), any(), any(), any()))
                .thenReturn(new CheckDayResponseDTO(CheckDayOwnerType.USER, userId, from, to,
                        twentyEightDays(from)));

        mockMvc.perform(get("/check-history").param("ownerType", "USER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-04-23"))
                .andExpect(jsonPath("$.to").value("2026-05-20"))
                .andExpect(jsonPath("$.days.length()").value(28));

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(checkHistoryService).history(eq(user), eq(CheckDayOwnerType.USER), eq(null),
                fromCaptor.capture(), toCaptor.capture());
        org.assertj.core.api.Assertions.assertThat(fromCaptor.getValue()).isNull();
        org.assertj.core.api.Assertions.assertThat(toCaptor.getValue()).isNull();
    }

    @Test
    void theUserOwnerTypeWithNoOwnerIdReachesTheServiceWithANullIdToResolve() throws Exception {
        LocalDate day = LocalDate.of(2026, 6, 1);
        when(checkHistoryService.history(any(), any(), any(), any(), any()))
                .thenReturn(new CheckDayResponseDTO(CheckDayOwnerType.USER, userId, day, day,
                        List.of(day(day, CheckDayResponseDTO.Outcome.DONE))));

        mockMvc.perform(get("/check-history")
                        .param("ownerType", "USER")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-01"))
                .andExpect(status().isOk())
                // The service resolved it to the caller, and the response says so.
                .andExpect(jsonPath("$.ownerId").value(userId.toString()));

        verify(checkHistoryService).history(eq(user), eq(CheckDayOwnerType.USER), eq(null),
                eq(day), eq(day));
    }

    @Test
    void aLowercaseOwnerTypeIsAccepted() throws Exception {
        LocalDate day = LocalDate.of(2026, 6, 1);
        when(checkHistoryService.history(any(), any(), any(), any(), any()))
                .thenReturn(new CheckDayResponseDTO(CheckDayOwnerType.ROUTINE, habitId, day, day,
                        List.of(day(day, CheckDayResponseDTO.Outcome.NOT_SCHEDULED))));

        mockMvc.perform(get("/check-history")
                        .param("ownerType", "routine")
                        .param("ownerId", habitId.toString()))
                .andExpect(status().isOk());

        verify(checkHistoryService).history(eq(user), eq(CheckDayOwnerType.ROUTINE), eq(habitId),
                any(), any());
    }

    /**
     * A hand-edited query string is the only way to get here, and without the controller's
     * own parsing it would answer a bare 400 with an empty body — outside the
     * {@code errorKey} envelope every client parses.
     */
    @Test
    void anUnknownOwnerTypeIsRefusedInsideTheErrorEnvelope() throws Exception {
        mockMvc.perform(get("/check-history").param("ownerType", "CATEGORY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("HABIT, TASK, ROUTINE, USER")));

        verify(checkHistoryService, never()).history(any(), any(), any(), any(), any());
    }

    @Test
    void aMissingOwnerTypeIsRefusedInsideTheErrorEnvelope() throws Exception {
        mockMvc.perform(get("/check-history").param("ownerId", habitId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));

        verify(checkHistoryService, never()).history(any(), any(), any(), any(), any());
    }

    // --- helpers ------------------------------------------------------------

    private static CheckDayResponseDTO.Day day(LocalDate day, CheckDayResponseDTO.Outcome outcome) {
        return new CheckDayResponseDTO.Day(day, outcome);
    }

    private static List<CheckDayResponseDTO.Day> twentyEightDays(LocalDate from) {
        List<CheckDayResponseDTO.Day> days = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            days.add(day(from.plusDays(i), CheckDayResponseDTO.Outcome.UNKNOWN));
        }
        return days;
    }
}
