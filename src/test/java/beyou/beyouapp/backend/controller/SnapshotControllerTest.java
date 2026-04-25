package beyou.beyouapp.backend.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import beyou.beyouapp.backend.AbstractIntegrationTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckService;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotCheckRequestDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotCheckResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotMonthResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotResponseDTO;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUserDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
class SnapshotControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SnapshotService snapshotService;

    @MockitoBean
    private SnapshotCheckService snapshotCheckService;

    @MockitoBean
    private AuthenticatedUser authenticatedUser;

    private ObjectMapper objectMapper;

    private User user;
    private UUID userId;
    private UUID routineId;
    private UUID snapshotId;
    private UUID snapshotCheckId;
    // Snapshot endpoints return RefreshUiDTO with only refreshUser populated (no categories/habit/itemChecked)
    private RefreshUiDTO refreshUiDTO = new RefreshUiDTO(
            new RefreshUserDTO(3, false, 7, 120.5, 2, 20.0, 130.0),
            null, null, null);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        userId = UUID.randomUUID();
        routineId = UUID.randomUUID();
        snapshotId = UUID.randomUUID();
        snapshotCheckId = UUID.randomUUID();

        user = new User();
        user.setId(userId);

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void shouldGetSnapshotByRoutineIdAndDate() throws Exception {
        LocalDate date = LocalDate.of(2025, 3, 15);

        SnapshotResponseDTO responseDTO = new SnapshotResponseDTO(
                snapshotId,
                date,
                "Morning Routine",
                "sun-icon",
                false,
                "{\"sections\":[]}",
                List.of(new SnapshotCheckResponseDTO(
                        snapshotCheckId,
                        SnapshotItemType.HABIT,
                        "Meditation",
                        "meditation-icon",
                        "Morning",
                        UUID.randomUUID(),
                        3,
                        2,
                        false,
                        false,
                        null,
                        0.0
                ))
        );

        when(snapshotService.getSnapshot(routineId, date, userId)).thenReturn(responseDTO);

        mockMvc.perform(get("/routine/{routineId}/snapshot", routineId)
                        .param("date", "2025-03-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(snapshotId.toString()))
                .andExpect(jsonPath("$.snapshotDate").value("2025-03-15"))
                .andExpect(jsonPath("$.routineName").value("Morning Routine"))
                .andExpect(jsonPath("$.routineIconId").value("sun-icon"))
                .andExpect(jsonPath("$.completed").value(false))
                .andExpect(jsonPath("$.checks", hasSize(1)))
                .andExpect(jsonPath("$.checks[0].id").value(snapshotCheckId.toString()))
                .andExpect(jsonPath("$.checks[0].itemType").value("HABIT"))
                .andExpect(jsonPath("$.checks[0].itemName").value("Meditation"));

        verify(snapshotService).getSnapshot(routineId, date, userId);
    }

    @Test
    void shouldGetSnapshotDatesForMonth() throws Exception {
        SnapshotMonthResponseDTO responseDTO = new SnapshotMonthResponseDTO(
                List.of(
                        LocalDate.of(2025, 3, 1),
                        LocalDate.of(2025, 3, 5),
                        LocalDate.of(2025, 3, 10)
                )
        );

        when(snapshotService.getSnapshotDatesForMonth(routineId, "2025-03", userId))
                .thenReturn(responseDTO);

        mockMvc.perform(get("/routine/{routineId}/snapshots", routineId)
                        .param("month", "2025-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates", hasSize(3)))
                .andExpect(jsonPath("$.dates[0]").value("2025-03-01"))
                .andExpect(jsonPath("$.dates[1]").value("2025-03-05"))
                .andExpect(jsonPath("$.dates[2]").value("2025-03-10"));

        verify(snapshotService).getSnapshotDatesForMonth(routineId, "2025-03", userId);
    }

    @Test
    void shouldCheckSnapshotItem() throws Exception {
        SnapshotCheckRequestDTO requestDTO = new SnapshotCheckRequestDTO(snapshotId, snapshotCheckId);

        when(snapshotCheckService.checkOrUncheckSnapshotItem(snapshotId, snapshotCheckId))
                .thenReturn(refreshUiDTO);

        mockMvc.perform(post("/routine/snapshot/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshUser.currentConstance").value(3))
                .andExpect(jsonPath("$.refreshUser.alreadyIncreaseConstanceToday").value(false))
                .andExpect(jsonPath("$.refreshUser.maxConstance").value(7))
                .andExpect(jsonPath("$.refreshUser.xp").value(120.5))
                .andExpect(jsonPath("$.refreshUser.level").value(2))
                .andExpect(jsonPath("$.refreshUser.actualLevelXp").value(20.0))
                .andExpect(jsonPath("$.refreshUser.nextLevelXp").value(130.0))
                .andExpect(jsonPath("$.refreshCategories").doesNotExist())
                .andExpect(jsonPath("$.refreshHabit").doesNotExist())
                .andExpect(jsonPath("$.refreshItemChecked").doesNotExist());

        verify(snapshotCheckService).checkOrUncheckSnapshotItem(snapshotId, snapshotCheckId);
    }

    @Test
    void shouldSkipSnapshotItem() throws Exception {
        SnapshotCheckRequestDTO requestDTO = new SnapshotCheckRequestDTO(snapshotId, snapshotCheckId);

        when(snapshotCheckService.skipOrUnskipSnapshotItem(snapshotId, snapshotCheckId))
                .thenReturn(refreshUiDTO);

        mockMvc.perform(post("/routine/snapshot/skip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshUser.currentConstance").value(3))
                .andExpect(jsonPath("$.refreshUser.alreadyIncreaseConstanceToday").value(false))
                .andExpect(jsonPath("$.refreshUser.maxConstance").value(7))
                .andExpect(jsonPath("$.refreshUser.xp").value(120.5))
                .andExpect(jsonPath("$.refreshUser.level").value(2))
                .andExpect(jsonPath("$.refreshCategories").doesNotExist())
                .andExpect(jsonPath("$.refreshHabit").doesNotExist())
                .andExpect(jsonPath("$.refreshItemChecked").doesNotExist());

        verify(snapshotCheckService).skipOrUnskipSnapshotItem(snapshotId, snapshotCheckId);
    }
}
