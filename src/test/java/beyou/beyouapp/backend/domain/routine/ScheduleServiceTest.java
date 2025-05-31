package beyou.beyouapp.backend.domain.routine;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.UpdateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.exceptions.routine.ScheduleNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private DiaryRoutineService diaryRoutineService;

    @InjectMocks
    private ScheduleService scheduleService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void shouldReturnAllSchedulesForUser() {
        UUID userId = UUID.randomUUID();
        Schedule schedule1 = new Schedule();
        Schedule schedule2 = new Schedule();

        DiaryRoutine routine1 = new DiaryRoutine();
        routine1.setSchedule(schedule1);

        DiaryRoutine routine2 = new DiaryRoutine();
        routine2.setSchedule(schedule2);

        when(diaryRoutineService.getAllDiaryRoutinesModels(userId))
                .thenReturn(List.of(routine1, routine2));

        List<Schedule> result = scheduleService.findAll(userId);

        assertEquals(2, result.size());
        assertTrue(result.contains(schedule1));
        assertTrue(result.contains(schedule2));
    }

    @Test
    void testFindById_found() {
        UUID id = UUID.randomUUID();
        Schedule schedule = new Schedule();
        when(scheduleRepository.findById(id)).thenReturn(Optional.of(schedule));

        Optional<Schedule> result = scheduleService.findById(id);

        assertTrue(result.isPresent());
        assertEquals(schedule, result.get());
    }

    @Test
    void testFindById_notFound() {
        UUID id = UUID.randomUUID();
        when(scheduleRepository.findById(id)).thenReturn(Optional.empty());

        Optional<Schedule> result = scheduleService.findById(id);

        assertFalse(result.isPresent());
    }

    @Test
    void testCreate() {
        UUID userId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();
        Set<String> days = Set.of("MONDAY", "WEDNESDAY");
        CreateScheduleDTO dto = new CreateScheduleDTO(days, routineId);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setSchedule(null);

        when(diaryRoutineService.getDiaryRoutineModelById(routineId, userId)).thenReturn(routine);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Schedule result = scheduleService.create(dto, userId);

        assertEquals(days, result.getDays());
        verify(diaryRoutineService).updateSchedule(any(DiaryRoutine.class));
    }

    @Test
    void testUpdate_existingSchedule() {
        UUID userId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        Set<String> days = Set.of("FRIDAY");
        UpdateScheduleDTO dto = new UpdateScheduleDTO(scheduleId, days, routineId);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);

        Schedule schedule = new Schedule();
        schedule.setId(scheduleId);
        schedule.setDays(Set.of("MONDAY"));

        when(diaryRoutineService.getDiaryRoutineModelById(routineId, userId)).thenReturn(routine);
        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.of(schedule));
        when(scheduleRepository.save(schedule)).thenReturn(schedule);

        Schedule result = scheduleService.update(dto, userId);

        assertEquals(days, result.getDays());
    }

    @Test
    void testUpdate_scheduleNotFound() {
        UUID userId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();

        UpdateScheduleDTO dto = new UpdateScheduleDTO(scheduleId, Set.of("MONDAY"), routineId);

        when(scheduleRepository.findById(scheduleId)).thenReturn(Optional.empty());

        assertThrows(ScheduleNotFoundException.class, () -> {
            scheduleService.update(dto, userId);
        });
    }

    @Test
    void testDelete_existingSchedule() {
        UUID userId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();

        when(scheduleRepository.existsById(scheduleId)).thenReturn(true);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        routine.setSchedule(new Schedule());

        when(diaryRoutineService.getDiaryRoutineByScheduleId(scheduleId, userId)).thenReturn(routine);

        scheduleService.delete(scheduleId, userId);

        assertNull(routine.getSchedule());
        verify(scheduleRepository).deleteById(scheduleId);
    }

    @Test
    void testDelete_notFound() {
        UUID userId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();

        when(scheduleRepository.existsById(scheduleId)).thenReturn(false);

        assertThrows(ScheduleNotFoundException.class, () -> {
            scheduleService.delete(scheduleId, userId);
        });
    }
}
