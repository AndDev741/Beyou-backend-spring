package beyou.beyouapp.backend.unit.routine;

import beyou.beyouapp.backend.domain.common.UserCacheEvictService;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.routine.checks.CheckItemService;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineMapper;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import beyou.beyouapp.backend.user.User;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class DiaryRoutineServiceUnitTest {

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @Mock
    private TaskService taskService;

    @Mock
    private HabitService habitService;

    @Mock
    private CheckItemService checkItemService;

    @Mock
    private UserCacheEvictService userCacheEvictService;

    private DiaryRoutineMapper mapper;

    private DiaryRoutineService diaryRoutineService;

    private DiaryRoutineRequestDTO validRequestDTO;
    private DiaryRoutine diaryRoutine;
    private Task mockedTask;
    private Habit mockedHabit;
    private UUID routineId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        mapper = new DiaryRoutineMapper(taskService, habitService);
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
                                                LocalTime.of(7, 0),
                                                null)),

                                        List.of(new HabitGroupDTO(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                LocalTime.of(6, 15),
                                                LocalTime.of(6, 45),
                                                null)),
                                        false
                                        ))));

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
        mockedTask.setUser(user);

        Task task = new Task();
        task.setId(validRequestDTO.routineSections().get(0).taskGroup().get(0).taskId());
        taskGroup.setTask(task);
        taskGroup.setStartTime(validRequestDTO.routineSections().get(0).taskGroup().get(0).startTime());
        taskGroup.setEndTime(validRequestDTO.routineSections().get(0).taskGroup().get(0).endTime());
        taskGroup.setRoutineSection(section);
        taskGroup.setTaskGroupChecks(new ArrayList<>());
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(UUID.randomUUID());

        mockedHabit = new Habit();
        mockedHabit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());
        mockedHabit.setUser(user);

        Habit habit = new Habit();
        habit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());
        habitGroup.setHabit(habit);
        habitGroup.setStartTime(validRequestDTO.routineSections().get(0).habitGroup().get(0).startTime());
        habitGroup.setEndTime(validRequestDTO.routineSections().get(0).habitGroup().get(0).endTime());
        habitGroup.setRoutineSection(section);
        habitGroup.setHabitGroupChecks(new ArrayList<>(new ArrayList<>()));
        section.setTaskGroups(new ArrayList<>(List.of(taskGroup)));
        section.setHabitGroups(new ArrayList<>(List.of(habitGroup)));
        section.setRoutine(diaryRoutine);
        diaryRoutine.setRoutineSections(new ArrayList<>(List.of(section)));

        diaryRoutineService = new DiaryRoutineService(diaryRoutineRepository, mapper, checkItemService, userCacheEvictService, habitService, taskService);
    }

    @Test
    @DisplayName("Should create diary routine successfully")
    void shouldCreateDiaryRoutineSuccessfully() {
        when(diaryRoutineRepository.save(any(DiaryRoutine.class))).thenReturn(diaryRoutine);

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

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> diaryRoutineService.createDiaryRoutine(invalidDTO, new User()));
        assertEquals(ErrorKey.ROUTINE_NAME_REQUIRED, exception.getErrorKey());
        assertEquals("DiaryRoutine name cannot be null or empty", exception.getMessage());
        verify(diaryRoutineRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should allow overnight routine section time range")
    void shouldAllowOvernightSectionTimeRange() {
        DiaryRoutineRequestDTO overnightDTO = new DiaryRoutineRequestDTO(
                "Rotina Diária",
                "routine-icon-123",
                List.of(
                        new RoutineSectionRequestDTO(
                                null,
                                "Manhã Produtiva",
                                "morning-icon-456",
                                LocalTime.of(22, 0),
                                LocalTime.of(6, 0),
                                List.of(),
                                List.of(),
                                false
                                )));

        when(diaryRoutineRepository.save(any(DiaryRoutine.class))).thenReturn(diaryRoutine);

        assertDoesNotThrow(() -> diaryRoutineService.createDiaryRoutine(overnightDTO, new User()));
        verify(diaryRoutineRepository, times(1)).save(any(DiaryRoutine.class));
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when item endTime is before item startTime")
    void shouldThrowExceptionWhenItemEndTimeBeforeStartTime() {
        DiaryRoutineRequestDTO invalidDTO = new DiaryRoutineRequestDTO(
                "Rotina Diária",
                "routine-icon-123",
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
                                        LocalTime.of(8, 0),
                                        LocalTime.of(7, 0),
                                        null)),
                                List.of(),
                                false
                        )));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> diaryRoutineService.createDiaryRoutine(invalidDTO, new User()));
        assertEquals(ErrorKey.ITEM_END_BEFORE_START, exception.getErrorKey());
        assertEquals("End time must be after start time for task in routine section: Manhã Produtiva",
                exception.getMessage());
        verify(diaryRoutineRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when item endTime is outside section bounds")
    void shouldThrowExceptionWhenItemEndTimeOutsideSectionBounds() {
        DiaryRoutineRequestDTO invalidDTO = new DiaryRoutineRequestDTO(
                "Rotina Diária",
                "routine-icon-123",
                List.of(
                        new RoutineSectionRequestDTO(
                                null,
                                "Manhã Produtiva",
                                "morning-icon-456",
                                LocalTime.of(6, 0),
                                LocalTime.of(12, 0),
                                List.of(),
                                List.of(new HabitGroupDTO(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        LocalTime.of(11, 0),
                                        LocalTime.of(13, 0),
                                        null)),
                                false
                        )));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> diaryRoutineService.createDiaryRoutine(invalidDTO, new User()));
        assertEquals(ErrorKey.ITEM_END_OUT_OF_SECTION, exception.getErrorKey());
        assertEquals("End time must be within section bounds for habit in routine section: Manhã Produtiva",
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
                                                LocalTime.of(7, 0),
                                                null)),

                                        List.of(new HabitGroupDTO(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                LocalTime.of(6, 15),
                                                LocalTime.of(6, 45),
                                                null)),
                                        false
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
        taskGroup.setEndTime(validRequestDTO.routineSections().get(0).taskGroup().get(0).endTime());
        taskGroup.setRoutineSection(updatedSection);
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(UUID.randomUUID());

        mockedHabit = new Habit();
        mockedHabit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());
        mockedHabit.setUser(user);

        Habit habit = new Habit();
        habit.setId(validRequestDTO.routineSections().get(0).habitGroup().get(0).habitId());
        habitGroup.setHabit(habit);
        habitGroup.setStartTime(validRequestDTO.routineSections().get(0).habitGroup().get(0).startTime());
        habitGroup.setEndTime(validRequestDTO.routineSections().get(0).habitGroup().get(0).endTime());
        habitGroup.setRoutineSection(updatedSection);
        updatedSection.setTaskGroups(List.of(taskGroup));
        updatedSection.setHabitGroups(List.of(habitGroup));
        updatedSection.setRoutine(diaryRoutine);
        diaryRoutine.setRoutineSections(new ArrayList<>(List.of(updatedSection)));

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));
        when(taskService.getTask(any(UUID.class))).thenReturn(mockedTask);
        when(habitService.getHabit(any(UUID.class))).thenReturn(mockedHabit);

        DiaryRoutineResponseDTO response = diaryRoutineService.updateDiaryRoutine(routineId, updatedDTO, userId);

        assertNotNull(response);
        assertEquals(routineId, response.id());
        assertEquals(updatedDTO.name(), response.name());
        assertEquals(updatedDTO.iconId(), response.iconId());
        assertEquals(updatedDTO.routineSections().get(0).name(), response.routineSections().get(0).name());
        verify(diaryRoutineRepository, times(1)).findById(routineId);
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
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> diaryRoutineService.deleteDiaryRoutine(routineId, userId));
        assertEquals(ErrorKey.ROUTINE_NOT_OWNED, exception.getErrorKey());
        assertEquals("The user trying to get its different of the one in the object", exception.getMessage());
        verify(diaryRoutineRepository, never()).deleteById(any());
    }

    @Test
    void shouldReturnTodayRoutine_whenRoutineIsScheduledForToday() {
        // Arrange
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);
        String today = LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

        Schedule schedule = new Schedule();
        schedule.setDays(Set.of(WeekDay.valueOf(today)));

        diaryRoutine.setName("Morning Routine");
        diaryRoutine.setSchedule(schedule);
        diaryRoutine.setUser(user);

        when(diaryRoutineRepository.findAllByUserId(userId))
                .thenReturn(List.of(diaryRoutine));

        // Act
        DiaryRoutineResponseDTO result = diaryRoutineService.getTodayRoutineScheduled(userId);

        // Assert — comportamento esperado
        assertNotNull(result);
        assertEquals("Morning Routine", result.name());
    }

    @Test
    void shouldReturnNullWhenNoRoutineIsScheduledForToday() {
        // Arrange
        UUID userId = UUID.randomUUID();

        DiaryRoutine otherRoutine = new DiaryRoutine();
        otherRoutine.setName("Evening Routine");

        Schedule schedule = new Schedule();
        WeekDay today = WeekDay.valueOf(LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        WeekDay notToday = today == WeekDay.Monday ? WeekDay.Tuesday : WeekDay.Monday;
        schedule.setDays(Set.of(notToday));
        otherRoutine.setSchedule(schedule);

        when(diaryRoutineRepository.findAllByUserId(userId))
                .thenReturn(List.of(otherRoutine));

        // Act & Assert
        DiaryRoutineResponseDTO result = diaryRoutineService.getTodayRoutineScheduled(userId);

        assertNull(result);
    }

    // --- mergeSections: habitGroup/taskGroup merge tests ---

    @Test
    @DisplayName("Should remove habit groups when update sends empty habitGroup list")
    void shouldRemoveHabitGroupsWhenUpdateSendsEmptyList() {
        // The diaryRoutine from setUp has 1 section with 1 habitGroup and 1 taskGroup
        RoutineSection existingSection = diaryRoutine.getRoutineSections().get(0);
        UUID sectionId = existingSection.getId();
        UUID taskGroupId = existingSection.getTaskGroups().get(0).getId();

        assertEquals(1, existingSection.getHabitGroups().size(), "Precondition: should start with 1 habit group");

        // Send update with existing section ID but empty habitGroup list (keep taskGroup)
        DiaryRoutineRequestDTO updateDTO = new DiaryRoutineRequestDTO(
                "Updated",
                "icon",
                List.of(new RoutineSectionRequestDTO(
                        sectionId,
                        "Morning",
                        "sun",
                        LocalTime.of(6, 0),
                        LocalTime.of(12, 0),
                        List.of(new TaskGroupDTO(taskGroupId,
                                existingSection.getTaskGroups().get(0).getTask().getId(),
                                LocalTime.of(6, 30), LocalTime.of(7, 0), null)),
                        List.of(), // empty habitGroup — should remove the existing one
                        false)));

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));

        DiaryRoutineResponseDTO response = diaryRoutineService.updateDiaryRoutine(routineId, updateDTO, userId);

        assertNotNull(response);
        assertEquals(0, existingSection.getHabitGroups().size(), "Habit groups should be empty after removal");
        assertEquals(1, existingSection.getTaskGroups().size(), "Task groups should still be present");
    }

    @Test
    @DisplayName("Should remove task groups when update sends empty taskGroup list")
    void shouldRemoveTaskGroupsWhenUpdateSendsEmptyList() {
        RoutineSection existingSection = diaryRoutine.getRoutineSections().get(0);
        UUID sectionId = existingSection.getId();
        UUID habitGroupId = existingSection.getHabitGroups().get(0).getId();

        assertEquals(1, existingSection.getTaskGroups().size(), "Precondition: should start with 1 task group");

        DiaryRoutineRequestDTO updateDTO = new DiaryRoutineRequestDTO(
                "Updated",
                "icon",
                List.of(new RoutineSectionRequestDTO(
                        sectionId,
                        "Morning",
                        "sun",
                        LocalTime.of(6, 0),
                        LocalTime.of(12, 0),
                        List.of(), // empty taskGroup — should remove the existing one
                        List.of(new HabitGroupDTO(habitGroupId,
                                existingSection.getHabitGroups().get(0).getHabit().getId(),
                                LocalTime.of(6, 15), LocalTime.of(6, 45), null)),
                        false)));

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));

        DiaryRoutineResponseDTO response = diaryRoutineService.updateDiaryRoutine(routineId, updateDTO, userId);

        assertNotNull(response);
        assertEquals(0, existingSection.getTaskGroups().size(), "Task groups should be empty after removal");
        assertEquals(1, existingSection.getHabitGroups().size(), "Habit groups should still be present");
    }

    @Test
    @DisplayName("Should add new habit group to existing section")
    void shouldAddNewHabitGroupToExistingSection() {
        RoutineSection existingSection = diaryRoutine.getRoutineSections().get(0);
        UUID sectionId = existingSection.getId();
        UUID existingHabitGroupId = existingSection.getHabitGroups().get(0).getId();
        UUID existingTaskGroupId = existingSection.getTaskGroups().get(0).getId();

        UUID newHabitId = UUID.randomUUID();
        Habit newHabit = new Habit();
        newHabit.setId(newHabitId);
        User owner = new User();
        owner.setId(userId);
        newHabit.setUser(owner);

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));
        when(habitService.getHabit(newHabitId)).thenReturn(newHabit);

        DiaryRoutineRequestDTO updateDTO = new DiaryRoutineRequestDTO(
                "Updated",
                "icon",
                List.of(new RoutineSectionRequestDTO(
                        sectionId,
                        "Morning",
                        "sun",
                        LocalTime.of(6, 0),
                        LocalTime.of(12, 0),
                        List.of(new TaskGroupDTO(existingTaskGroupId,
                                existingSection.getTaskGroups().get(0).getTask().getId(),
                                LocalTime.of(6, 30), LocalTime.of(7, 0), null)),
                        List.of(
                                new HabitGroupDTO(existingHabitGroupId,
                                        existingSection.getHabitGroups().get(0).getHabit().getId(),
                                        LocalTime.of(6, 15), LocalTime.of(6, 45), null),
                                new HabitGroupDTO(null, // new group — no ID
                                        newHabitId,
                                        LocalTime.of(7, 0), LocalTime.of(7, 30), null)),
                        false)));

        DiaryRoutineResponseDTO response = diaryRoutineService.updateDiaryRoutine(routineId, updateDTO, userId);

        assertNotNull(response);
        assertEquals(2, existingSection.getHabitGroups().size(), "Should have 2 habit groups after adding one");
        verify(habitService).getHabit(newHabitId);
    }

    @Test
    @DisplayName("Should update existing habit group time and keep it")
    void shouldUpdateExistingHabitGroupTime() {
        RoutineSection existingSection = diaryRoutine.getRoutineSections().get(0);
        UUID sectionId = existingSection.getId();
        UUID habitGroupId = existingSection.getHabitGroups().get(0).getId();
        UUID taskGroupId = existingSection.getTaskGroups().get(0).getId();

        LocalTime newStart = LocalTime.of(7, 0);
        LocalTime newEnd = LocalTime.of(7, 30);

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));

        DiaryRoutineRequestDTO updateDTO = new DiaryRoutineRequestDTO(
                "Updated",
                "icon",
                List.of(new RoutineSectionRequestDTO(
                        sectionId,
                        "Morning",
                        "sun",
                        LocalTime.of(6, 0),
                        LocalTime.of(12, 0),
                        List.of(new TaskGroupDTO(taskGroupId,
                                existingSection.getTaskGroups().get(0).getTask().getId(),
                                LocalTime.of(6, 30), LocalTime.of(7, 0), null)),
                        List.of(new HabitGroupDTO(habitGroupId,
                                existingSection.getHabitGroups().get(0).getHabit().getId(),
                                newStart, newEnd, null)),
                        false)));

        diaryRoutineService.updateDiaryRoutine(routineId, updateDTO, userId);

        HabitGroup updated = existingSection.getHabitGroups().get(0);
        assertEquals(habitGroupId, updated.getId(), "Should keep the same habit group");
        assertEquals(newStart, updated.getStartTime(), "Start time should be updated");
        assertEquals(newEnd, updated.getEndTime(), "End time should be updated");
    }

    @Test
    @DisplayName("Should remove all groups when both lists are empty")
    void shouldRemoveAllGroupsWhenBothListsAreEmpty() {
        RoutineSection existingSection = diaryRoutine.getRoutineSections().get(0);
        UUID sectionId = existingSection.getId();

        assertEquals(1, existingSection.getHabitGroups().size());
        assertEquals(1, existingSection.getTaskGroups().size());

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));

        DiaryRoutineRequestDTO updateDTO = new DiaryRoutineRequestDTO(
                "Updated",
                "icon",
                List.of(new RoutineSectionRequestDTO(
                        sectionId,
                        "Morning",
                        "sun",
                        LocalTime.of(6, 0),
                        LocalTime.of(12, 0),
                        List.of(), // empty tasks
                        List.of(), // empty habits
                        false)));

        diaryRoutineService.updateDiaryRoutine(routineId, updateDTO, userId);

        assertEquals(0, existingSection.getHabitGroups().size(), "All habit groups should be removed");
        assertEquals(0, existingSection.getTaskGroups().size(), "All task groups should be removed");
    }

    @Test
    @DisplayName("Should reject habit from different user (IDOR prevention)")
    void shouldRejectHabitFromDifferentUser() {
        RoutineSection existingSection = diaryRoutine.getRoutineSections().get(0);
        UUID sectionId = existingSection.getId();
        UUID existingTaskGroupId = existingSection.getTaskGroups().get(0).getId();

        UUID foreignHabitId = UUID.randomUUID();
        Habit foreignHabit = new Habit();
        foreignHabit.setId(foreignHabitId);
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID()); // different user
        foreignHabit.setUser(otherUser);

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));
        when(habitService.getHabit(foreignHabitId)).thenReturn(foreignHabit);

        DiaryRoutineRequestDTO updateDTO = new DiaryRoutineRequestDTO(
                "Updated",
                "icon",
                List.of(new RoutineSectionRequestDTO(
                        sectionId,
                        "Morning",
                        "sun",
                        LocalTime.of(6, 0),
                        LocalTime.of(12, 0),
                        List.of(new TaskGroupDTO(existingTaskGroupId,
                                existingSection.getTaskGroups().get(0).getTask().getId(),
                                LocalTime.of(6, 30), LocalTime.of(7, 0), null)),
                        List.of(new HabitGroupDTO(null, // new group with foreign habit
                                foreignHabitId,
                                LocalTime.of(7, 0), LocalTime.of(7, 30), null)),
                        false)));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> diaryRoutineService.updateDiaryRoutine(routineId, updateDTO, userId));
        assertEquals(ErrorKey.HABIT_NOT_OWNED, exception.getErrorKey());
    }

    @Test
    @DisplayName("Should reject task from different user (IDOR prevention)")
    void shouldRejectTaskFromDifferentUser() {
        RoutineSection existingSection = diaryRoutine.getRoutineSections().get(0);
        UUID sectionId = existingSection.getId();
        UUID existingHabitGroupId = existingSection.getHabitGroups().get(0).getId();

        UUID foreignTaskId = UUID.randomUUID();
        Task foreignTask = new Task();
        foreignTask.setId(foreignTaskId);
        User otherUser = new User();
        otherUser.setId(UUID.randomUUID()); // different user
        foreignTask.setUser(otherUser);

        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.of(diaryRoutine));
        when(taskService.getTask(foreignTaskId)).thenReturn(foreignTask);

        DiaryRoutineRequestDTO updateDTO = new DiaryRoutineRequestDTO(
                "Updated",
                "icon",
                List.of(new RoutineSectionRequestDTO(
                        sectionId,
                        "Morning",
                        "sun",
                        LocalTime.of(6, 0),
                        LocalTime.of(12, 0),
                        List.of(new TaskGroupDTO(null, // new group with foreign task
                                foreignTaskId,
                                LocalTime.of(6, 30), LocalTime.of(7, 0), null)),
                        List.of(new HabitGroupDTO(existingHabitGroupId,
                                existingSection.getHabitGroups().get(0).getHabit().getId(),
                                LocalTime.of(6, 15), LocalTime.of(6, 45), null)),
                        false)));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> diaryRoutineService.updateDiaryRoutine(routineId, updateDTO, userId));
        assertEquals(ErrorKey.TASK_NOT_OWNED, exception.getErrorKey());
    }
}
