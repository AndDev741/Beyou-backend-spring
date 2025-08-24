package beyou.beyouapp.backend.domain.routine;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.HabitGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.TaskGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.TaskGroupRequestDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DiaryRoutineServiceTest {

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    private TaskService taskService;

    @Mock
    private HabitService habitService;

    @InjectMocks
    private DiaryRoutineService diaryRoutineService;

    private DiaryRoutineRequestDTO validRequestDTO;
    private DiaryRoutine diaryRoutine;
    private Task mockedTask;
    private Habit mockedHabit;
    private UUID routineId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        routineId = UUID.randomUUID();
        userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);

        validRequestDTO = new DiaryRoutineRequestDTO(
                "Rotina Diária",
                "routine-icon-123",
                new ArrayList<>(
                        List.of(
                                new RoutineSectionRequestDTO(
                                        null,
                                        "Manhã Produtiva",
                                        "morning-icon-456",
                                        LocalTime.of(6, 0),
                                        LocalTime.of(12, 0),
                                        List.of(new TaskGroupDTO(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                LocalTime.of(6, 30),
                                                null)),

                                        List.of(new HabitGroupDTO(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                LocalTime.of(6, 15),
                                                null))))));

        diaryRoutine = new DiaryRoutine();
        diaryRoutine.setId(routineId);
        diaryRoutine.setName(validRequestDTO.name());
        diaryRoutine.setIconId(validRequestDTO.iconId());
        diaryRoutine.setUser(user);
        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setName(validRequestDTO.routineSections().get(0).name());
        section.setIconId(validRequestDTO.routineSections().get(0).iconId());
        section.setStartTime(validRequestDTO.routineSections().get(0).startTime());
        section.setEndTime(validRequestDTO.routineSections().get(0).endTime());
        TaskGroup taskGroup = new TaskGroup();
        taskGroup.setId(UUID.randomUUID());

        mockedTask = new Task();
        mockedTask.setId(validRequestDTO.routineSections().get(0).taskGroup().get(0).taskId());

        Task task = new Task();
        task.setId(validRequestDTO.routineSections().get(0).taskGroup().get(0).taskId());
        taskGroup.setTask(task);
        taskGroup.setStartTime(validRequestDTO.routineSections().get(0).taskGroup().get(0).startTime());
        taskGroup.setRoutineSection(section);
        taskGroup.setTaskGroupChecks(new ArrayList<>());
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(UUID.randomUUID());

        mockedHabit = new Habit();
        mockedHabit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());

        Habit habit = new Habit();
        habit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());
        habitGroup.setHabit(habit);
        habitGroup.setStartTime(validRequestDTO.routineSections().get(0).habitGroup().get(0).startTime());
        habitGroup.setRoutineSection(section);
        habitGroup.setHabitGroupChecks(new ArrayList<>(new ArrayList<>()));
        section.setTaskGroups(new ArrayList<>(List.of(taskGroup)));
        section.setHabitGroups(new ArrayList<>(List.of(habitGroup)));
        section.setRoutine(diaryRoutine);
        diaryRoutine.setRoutineSections(new ArrayList<>(List.of(section)));
    }

    @Test
    @DisplayName("Should create diary routine successfully")
    void shouldCreateDiaryRoutineSuccessfully() {
        when(diaryRoutineRepository.save(any(DiaryRoutine.class))).thenReturn(diaryRoutine);
        when(taskService.getTask(any(UUID.class))).thenReturn(mockedTask);
        when(habitService.getHabit(any(UUID.class))).thenReturn(mockedHabit);

        DiaryRoutineResponseDTO response = diaryRoutineService.createDiaryRoutine(validRequestDTO, new User());

        assertNotNull(response);
        assertEquals(routineId, response.id());
        assertEquals(validRequestDTO.name(), response.name());
        assertEquals(validRequestDTO.iconId(), response.iconId());
        assertEquals(1, response.routineSections().size());
        assertEquals(validRequestDTO.routineSections().get(0).name(), response.routineSections().get(0).name());
        verify(diaryRoutineRepository, times(1)).save(any(DiaryRoutine.class));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when creating with empty name")
    void shouldThrowExceptionWhenCreatingWithEmptyName() {
        DiaryRoutineRequestDTO invalidDTO = new DiaryRoutineRequestDTO(
                "",
                "routine-icon-123",
                validRequestDTO.routineSections());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> diaryRoutineService.createDiaryRoutine(invalidDTO, new User()));
        assertEquals("DiaryRoutine name cannot be null or empty", exception.getMessage());
        verify(diaryRoutineRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when endTime is before startTime")
    void shouldThrowExceptionWhenEndTimeBeforeStartTime() {
        DiaryRoutineRequestDTO invalidDTO = new DiaryRoutineRequestDTO(
                "Rotina Diária",
                "routine-icon-123",
                List.of(
                        new RoutineSectionRequestDTO(
                                null,
                                "Manhã Produtiva",
                                "morning-icon-456",
                                LocalTime.of(12, 0),
                                LocalTime.of(6, 0),
                                List.of(),
                                List.of())));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> diaryRoutineService.createDiaryRoutine(invalidDTO, new User()));
        assertEquals("End time must be after start time for routine section: Manhã Produtiva",
                exception.getMessage());
        verify(diaryRoutineRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should get diary routine by ID successfully")
    void shouldGetDiaryRoutineByIdSuccessfully() {
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));

        DiaryRoutineResponseDTO response = diaryRoutineService.getDiaryRoutineById(routineId, userId);

        assertNotNull(response);
        assertEquals(routineId, response.id());
        assertEquals(validRequestDTO.name(), response.name());
        verify(diaryRoutineRepository, times(1)).findById(routineId);
    }

    @Test
    @DisplayName("Should throw DiaryRoutineNotFoundException when getting non-existent routine")
    void shouldThrowNotFoundWhenGettingNonExistentRoutine() {
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());

        DiaryRoutineNotFoundException exception = assertThrows(
                DiaryRoutineNotFoundException.class,
                () -> diaryRoutineService.getDiaryRoutineById(routineId, userId));
        assertEquals("Diary routine not found by id", exception.getMessage());
        verify(diaryRoutineRepository, times(1)).findById(routineId);
    }

    @Test
    @DisplayName("Should get all diary routines successfully")
    void shouldGetAllDiaryRoutinesSuccessfully() {
        when(diaryRoutineRepository.findAllByUserId(userId)).thenReturn(List.of(diaryRoutine));

        List<DiaryRoutineResponseDTO> response = diaryRoutineService.getAllDiaryRoutines(userId);

        assertNotNull(response);
        assertEquals(1, response.size());
        assertEquals(routineId, response.get(0).id());
        verify(diaryRoutineRepository, times(1)).findAllByUserId(userId);
    }

    @Test
    @DisplayName("Should update diary routine successfully")
    void shouldUpdateDiaryRoutineSuccessfully() {
        DiaryRoutineRequestDTO updatedDTO = new DiaryRoutineRequestDTO(
                "New routine updated",
                "routine-icon-updated",
                new ArrayList<>(
                        List.of(
                                new RoutineSectionRequestDTO(
                                        null,
                                        "Manhã Produtiva",
                                        "morning-icon-456",
                                        LocalTime.of(6, 0),
                                        LocalTime.of(12, 0),
                                        List.of(new TaskGroupDTO(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                LocalTime.of(6, 30),
                                                null)),

                                        List.of(new HabitGroupDTO(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                LocalTime.of(6, 15),
                                                null))
                ))));

        User user = new User();
        user.setId(userId);
        DiaryRoutine updatedRoutine = new DiaryRoutine();
        updatedRoutine.setId(routineId);
        updatedRoutine.setName(updatedDTO.name());
        updatedRoutine.setIconId(updatedDTO.iconId());
        updatedRoutine.setUser(user);
        RoutineSection updatedSection = new RoutineSection();
        updatedSection.setId(UUID.randomUUID());
        updatedSection.setName(updatedDTO.routineSections().get(0).name());
        updatedSection.setIconId(updatedDTO.routineSections().get(0).iconId());
        updatedSection.setStartTime(updatedDTO.routineSections().get(0).startTime());
        updatedSection.setEndTime(updatedDTO.routineSections().get(0).endTime());
        updatedSection.setRoutine(updatedRoutine);
        updatedRoutine.setRoutineSections(new ArrayList<>(List.of(updatedSection)));

        TaskGroup taskGroup = new TaskGroup();
        taskGroup.setId(UUID.randomUUID());
        Task task = new Task();
        task.setId(validRequestDTO.routineSections().get(0).taskGroup().get(0).taskId());
        taskGroup.setTask(task);
        taskGroup.setStartTime(validRequestDTO.routineSections().get(0).taskGroup().get(0).startTime());
        taskGroup.setRoutineSection(updatedSection);
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(UUID.randomUUID());

        mockedHabit = new Habit();
        mockedHabit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());

        Habit habit = new Habit();
        habit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());
        habitGroup.setHabit(habit);
        habitGroup.setStartTime(validRequestDTO.routineSections().get(0).habitGroup().get(0).startTime());
        habitGroup.setRoutineSection(updatedSection);
        updatedSection.setTaskGroups(List.of(taskGroup));
        updatedSection.setHabitGroups(List.of(habitGroup));
        updatedSection.setRoutine(diaryRoutine);
        diaryRoutine.setRoutineSections(new ArrayList<>(List.of(updatedSection)));

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));
        when(diaryRoutineRepository.save(any(DiaryRoutine.class))).thenReturn(updatedRoutine);

        DiaryRoutineResponseDTO response = diaryRoutineService.updateDiaryRoutine(routineId, updatedDTO, userId);

        assertNotNull(response);
        assertEquals(routineId, response.id());
        assertEquals(updatedDTO.name(), response.name());
        assertEquals(updatedDTO.iconId(), response.iconId());
        assertEquals(updatedDTO.routineSections().get(0).name(), response.routineSections().get(0).name());
        verify(diaryRoutineRepository, times(1)).findById(routineId);
        verify(diaryRoutineRepository, times(1)).save(any(DiaryRoutine.class));
    }

    @Test
    @DisplayName("Should throw DiaryRoutineNotFoundException when updating non-existent routine")
    void shouldThrowNotFoundWhenUpdatingNonExistentRoutine() {
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());

        DiaryRoutineNotFoundException exception = assertThrows(
                DiaryRoutineNotFoundException.class,
                () -> diaryRoutineService.updateDiaryRoutine(routineId, validRequestDTO, userId));
        assertEquals("Diary routine not found by id", exception.getMessage());
        verify(diaryRoutineRepository, times(1)).findById(routineId);
        verify(diaryRoutineRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should delete diary routine successfully")
    void shouldDeleteDiaryRoutineSuccessfully() {
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));

        diaryRoutineService.deleteDiaryRoutine(routineId, userId);

        verify(diaryRoutineRepository, times(1)).findById(routineId);
        verify(diaryRoutineRepository, times(1)).deleteById(routineId);
    }

    @Test
    @DisplayName("Should throw DiaryRoutineNotFoundException when deleting non-existent routine")
    void shouldThrowNotFoundWhenDeletingNonExistentRoutine() {
        DiaryRoutineNotFoundException exception = assertThrows(
                DiaryRoutineNotFoundException.class,
                () -> diaryRoutineService.deleteDiaryRoutine(routineId, userId));
        assertEquals("The user trying to get its different of the one in the object", exception.getMessage());
        // verify(diaryRoutineRepository, times(1)).existsById(routineId);
        verify(diaryRoutineRepository, never()).deleteById(any());
    }

    @Test
    void shouldReturnTodayRoutine_whenRoutineIsScheduledForToday() {
        // Arrange
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);
        String today = LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        // Setup do Schedule
        Schedule schedule = new Schedule();
        schedule.setDays(Set.of(today)); // ✅ Use Set aqui, como sua entidade espera

        // Setup da DiaryRoutine
        diaryRoutine.setName("Morning Routine");
        diaryRoutine.setSchedule(schedule);
        diaryRoutine.setUser(user);

        // Simula retorno do repositório
        when(diaryRoutineRepository.findAllByUserId(userId))
                .thenReturn(List.of(diaryRoutine));

        // Act
        DiaryRoutineResponseDTO result = diaryRoutineService.getTodayRoutineScheduled(userId);

        // Assert — comportamento esperado
        assertNotNull(result);
        assertEquals("Morning Routine", result.name());
    }

    @Test
    void shouldThrowException_whenNoRoutineIsScheduledForToday() {
        // Arrange
        UUID userId = UUID.randomUUID();

        DiaryRoutine otherRoutine = new DiaryRoutine();
        otherRoutine.setName("Evening Routine");

        Schedule schedule = new Schedule();
        schedule.setDays((Set<String>) Set.of("Monday")); // Will not work in Monday haha!
        otherRoutine.setSchedule(schedule);

        when(diaryRoutineRepository.findAllByUserId(userId))
                .thenReturn(List.of(otherRoutine));

        // Act & Assert
        assertThrows(DiaryRoutineNotFoundException.class,
                () -> diaryRoutineService.getTodayRoutineScheduled(userId));
    }

    @Test
    void shouldThrowException_whenNoItemGroupInRequest() {
        // Arrange
        CheckGroupRequestDTO requestDTO = new CheckGroupRequestDTO(routineId, null, null);
        
        // Act & Assert
        assertThrows(RuntimeException.class,
                () -> diaryRoutineService.checkAndUncheckGroup(requestDTO, userId));
    }

    @Test
    void shouldCheckHabitGroup_whenNoExistingCheck() {
        // Arrange
        HabitGroup habitGroup = diaryRoutine.getRoutineSections().get(0).getHabitGroups().get(0);
        Habit habit = habitGroup.getHabit();
        habit.setDificulty(1);
        habit.setImportance(1);
        habit.setXp(0.0);
        habit.setNextLevelXp(Double.MAX_VALUE);
        habit.setConstance(0);
        habit.setCategories(new ArrayList<>());

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));

        CheckGroupRequestDTO requestDTO = new CheckGroupRequestDTO(
                routineId,
                new TaskGroupRequestDTO(null, null),
                new HabitGroupRequestDTO(habitGroup.getId(), null));

        // Act
        DiaryRoutineResponseDTO response = diaryRoutineService.checkAndUncheckGroup(requestDTO, userId);

        // Assert
        var habitGroups = response.routineSections().get(0).habitGroup();
        assertEquals(1, habitGroups.size());
        var checks = habitGroups.get(0).habitGroupChecks();
        assertEquals(1, checks.size());
        var check = checks.get(0);
        assertTrue(check.isChecked());
        assertEquals(LocalDate.now(), check.getCheckDate());
    }
}
