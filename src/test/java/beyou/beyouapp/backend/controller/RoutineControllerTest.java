package beyou.beyouapp.backend.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.TaskGroupRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.utils.RefreshUiDtoBuilder;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class RoutineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiaryRoutineService diaryRoutineService;

    @MockBean
    private AuthenticatedUser authenticatedUser;

    private ObjectMapper objectMapper;

    private User user;
    private UUID userId;
    private UUID routineId;
    private DiaryRoutineResponseDTO responseDto;
    private RefreshUiDTO refreshUiDTO = RefreshUiDtoBuilder.mockedRefreshUiDTO();

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        userId = UUID.randomUUID();
        routineId = UUID.randomUUID();
        user = new User();
        user.setId(userId);

        responseDto = new DiaryRoutineResponseDTO(
                routineId,
                "My routine",
                "icon",
                List.of(
                        new DiaryRoutineResponseDTO.RoutineSectionResponseDTO(
                                UUID.randomUUID(),
                                "Morning",
                                "sun",
                                "06:00",
                                "08:00",
                                List.of(new DiaryRoutineResponseDTO.RoutineSectionResponseDTO.TaskGroupResponseDTO(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "06:30",
                                        "07:00",
                                        List.of())),
                                List.of(new DiaryRoutineResponseDTO.RoutineSectionResponseDTO.HabitGroupResponseDTO(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "06:15",
                                        "06:45",
                                        List.of())),
                                false)),
                null,
                0,
                0,
                0,
                0
        );

        when(authenticatedUser.getAuthenticatedUser()).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    void shouldCreateDiaryRoutine() throws Exception {
        DiaryRoutineRequestDTO requestDTO = new DiaryRoutineRequestDTO(
                "My routine",
                "icon",
                List.of(new RoutineSectionRequestDTO(null, "Morning", "sun", null, null, List.of(), List.of(), false)));

        when(diaryRoutineService.createDiaryRoutine(requestDTO, user)).thenReturn(responseDto);

        mockMvc.perform(post("/routine")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/diary-routines/" + routineId))
                .andExpect(jsonPath("$.id").value(routineId.toString()))
                .andExpect(jsonPath("$.name").value("My routine"));

        verify(diaryRoutineService).createDiaryRoutine(requestDTO, user);
    }

    @Test
    void shouldGetDiaryRoutineById() throws Exception {
        when(diaryRoutineService.getDiaryRoutineById(routineId, userId)).thenReturn(responseDto);

        mockMvc.perform(get("/routine/{id}", routineId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(routineId.toString()))
                .andExpect(jsonPath("$.routineSections[0].taskGroup[0].endTime").value("07:00"))
                .andExpect(jsonPath("$.routineSections[0].habitGroup[0].endTime").value("06:45"));

        verify(diaryRoutineService).getDiaryRoutineById(routineId, userId);
    }

    @Test
    void shouldGetAllDiaryRoutines() throws Exception {
        when(diaryRoutineService.getAllDiaryRoutines(userId)).thenReturn(List.of(responseDto));

        mockMvc.perform(get("/routine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(routineId.toString()));

        verify(diaryRoutineService).getAllDiaryRoutines(userId);
    }

    @Test
    void shouldUpdateDiaryRoutine() throws Exception {
        DiaryRoutineRequestDTO requestDTO = new DiaryRoutineRequestDTO(
                "Updated routine",
                "icon-2",
                List.of(new RoutineSectionRequestDTO(null, "Evening", "moon", null, null, List.of(), List.of(), true)));

        DiaryRoutineResponseDTO updatedResponse = new DiaryRoutineResponseDTO(
                routineId,
                "Updated routine",
                "icon-2",
                responseDto.routineSections(),
                null,
                0,
                0,
                0,
                0
        );

        when(diaryRoutineService.updateDiaryRoutine(routineId, requestDTO, userId)).thenReturn(updatedResponse);

        mockMvc.perform(put("/routine/{id}", routineId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated routine"))
                .andExpect(jsonPath("$.iconId").value("icon-2"));

        verify(diaryRoutineService).updateDiaryRoutine(routineId, requestDTO, userId);
    }

    @Test
    void shouldDeleteDiaryRoutine() throws Exception {
        mockMvc.perform(delete("/routine/{id}", routineId))
                .andExpect(status().isNoContent());

        verify(diaryRoutineService).deleteDiaryRoutine(routineId, userId);
    }

    @Test
    void shouldGetTodayRoutineScheduled() throws Exception {
        when(diaryRoutineService.getTodayRoutineScheduled(userId)).thenReturn(responseDto);

        mockMvc.perform(get("/routine/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(routineId.toString()));

        verify(diaryRoutineService).getTodayRoutineScheduled(userId);
    }

    @Test
    void shouldCheckItemGroup() throws Exception {
        CheckGroupRequestDTO checkRequest = new CheckGroupRequestDTO(
                routineId,
                new TaskGroupRequestDTO(UUID.randomUUID(), null),
                null,
                LocalDate.now());

        when(diaryRoutineService.checkAndUncheckGroup(checkRequest, userId)).thenReturn(refreshUiDTO);

        mockMvc.perform(post("/routine/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(checkRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshUser.currentConstance").value(3))
                .andExpect(jsonPath("$.refreshUser.alreadyIncreaseConstanceToday").value(false))
                .andExpect(jsonPath("$.refreshUser.maxConstance").value(7))
                .andExpect(jsonPath("$.refreshUser.xp").value(120.5))
                .andExpect(jsonPath("$.refreshUser.level").value(2))
                .andExpect(jsonPath("$.refreshUser.actualLevelXp").value(20.0))
                .andExpect(jsonPath("$.refreshUser.nextLevelXp").value(130.0))
                .andExpect(jsonPath("$.refreshCategories", hasSize(2)))
                .andExpect(jsonPath("$.refreshCategories[0].id").value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.refreshCategories[1].id").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.refreshHabit.id").value("33333333-3333-3333-3333-333333333333"))
                .andExpect(jsonPath("$.refreshItemChecked.groupItemId").value("55555555-5555-5555-5555-555555555555"))
                .andExpect(jsonPath("$.refreshItemChecked.check.checked").value(true));

        verify(diaryRoutineService).checkAndUncheckGroup(checkRequest, userId);
    }
}
