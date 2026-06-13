package beyou.beyouapp.backend.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.ai.AiRoutineConfirmService;
import beyou.beyouapp.backend.domain.ai.AiRoutineService;
import beyou.beyouapp.backend.domain.ai.dto.DraftSectionDTO;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineRequestDTO;
import beyou.beyouapp.backend.domain.ai.dto.GenerateRoutineResponseDTO;
import beyou.beyouapp.backend.domain.ai.dto.RoutineDraftDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.exceptions.ai.AiGenerationException;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;

@AutoConfigureMockMvc(addFilters = false)
class AiRoutineControllerTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    // Constructed manually — Boot 4 auto-configures Jackson 3 (tools.jackson),
    // so there is no com.fasterxml ObjectMapper bean (same as RoutineControllerTest).
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private AiRoutineService aiRoutineService;
    @MockitoBean private AiRoutineConfirmService aiRoutineConfirmService;
    @MockitoBean private AuthenticatedUser authenticatedUser;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void generateReturnsDraft() throws Exception {
        RoutineDraftDTO draft = new RoutineDraftDTO("AI Routine", null, List.of(), List.of(), null);
        when(aiRoutineService.generate(any(GenerateRoutineRequestDTO.class), any(User.class)))
                .thenReturn(new GenerateRoutineResponseDTO(draft));

        mockMvc.perform(post("/ai/routine/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("description", "I wake up at 6am and want a productive morning"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft.name").value("AI Routine"));

        verify(aiRoutineService).generate(any(GenerateRoutineRequestDTO.class), any(User.class));
    }

    @Test
    void generateRejectsShortDescription() throws Exception {
        mockMvc.perform(post("/ai/routine/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("description", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorKey").value("INVALID_REQUEST"));
    }

    @Test
    void generateReturns503WhenProviderDown() throws Exception {
        when(aiRoutineService.generate(any(), any()))
                .thenThrow(new AiGenerationException("down"));

        mockMvc.perform(post("/ai/routine/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("description", "I wake up at 6am and want a productive morning"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorKey").value("AI_GENERATION_FAILED"));
    }

    @Test
    void confirmReturns201WithCreatedRoutine() throws Exception {
        UUID routineId = UUID.randomUUID();
        DiaryRoutineResponseDTO responseDto = new DiaryRoutineResponseDTO(
                routineId, "AI Routine", "ri:md/MdStar", List.of(), null, 0, 0, 0, 0);
        when(aiRoutineConfirmService.confirm(any(RoutineDraftDTO.class), any(User.class), isNull()))
                .thenReturn(responseDto);

        RoutineDraftDTO draft = new RoutineDraftDTO("AI Routine", null, List.of(),
                List.of(new DraftSectionDTO("Morning", null, "06:00", "09:00", List.of(), List.of())),
                null);

        mockMvc.perform(post("/ai/routine/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draft)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(routineId.toString()))
                .andExpect(jsonPath("$.name").value("AI Routine"));

        verify(aiRoutineConfirmService).confirm(any(RoutineDraftDTO.class), any(User.class), isNull());
    }

    @Test
    void confirmWithRoutineIdUpdatesExistingRoutineAndReturns200() throws Exception {
        UUID routineId = UUID.randomUUID();
        DiaryRoutineResponseDTO responseDto = new DiaryRoutineResponseDTO(
                routineId, "Updated AI Routine", "ri:md/MdStar", List.of(), null, 0, 0, 0, 0);
        when(aiRoutineConfirmService.confirm(any(RoutineDraftDTO.class), any(User.class), eq(routineId)))
                .thenReturn(responseDto);

        RoutineDraftDTO draft = new RoutineDraftDTO("Updated AI Routine", null, List.of(),
                List.of(new DraftSectionDTO("Evening", null, "20:00", "22:00", List.of(), List.of())),
                null);

        mockMvc.perform(post("/ai/routine/confirm")
                        .param("routineId", routineId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(draft)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(routineId.toString()))
                .andExpect(jsonPath("$.name").value("Updated AI Routine"));

        verify(aiRoutineConfirmService).confirm(any(RoutineDraftDTO.class), any(User.class), eq(routineId));
    }
}
