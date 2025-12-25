package beyou.beyouapp.backend.unit.schedule;

import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.schedule.dto.UpdateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.exceptions.routine.ScheduleNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceUnitTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private DiaryRoutineService diaryRoutineService;

    @InjectMocks
    private ScheduleService scheduleService;

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
        Set<WeekDay> days = Set.of(WeekDay.Monday, WeekDay.Wednesday);
        CreateScheduleDTO dto = new CreateScheduleDTO(days, routineId);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setSchedule(null);

        when(diaryRoutineService.getDiaryRoutineModelById(routineId, userId)).thenReturn(routine);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Schedule result = scheduleService.create(dto, userId);

        assertEquals(days, result.getDays());
        verify(diaryRoutineService).saveRoutine(any(DiaryRoutine.class));
    }

    @Test
    void testUpdate_existingSchedule() {
        UUID userId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();
        Set<WeekDay> days = Set.of(WeekDay.Friday);
        UpdateScheduleDTO dto = new UpdateScheduleDTO(scheduleId, days, routineId);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);

        Schedule schedule = new Schedule();
        schedule.setId(scheduleId);
        schedule.setDays(Set.of(WeekDay.Monday));

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

        UpdateScheduleDTO dto = new UpdateScheduleDTO(scheduleId, Set.of(WeekDay.Monday), routineId);

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

    @Test
    void shouldRemoveOverlappingDaysFromOtherSchedulesOnCreate() {
        UUID userId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();
        Set<WeekDay> newDays = Set.of(WeekDay.Monday, WeekDay.Tuesday);

        Schedule nonOverlappingSchedule = new Schedule();
        nonOverlappingSchedule.setDays(new HashSet<>(Set.of(WeekDay.Wednesday)));
        DiaryRoutine routineWithoutOverlap = new DiaryRoutine();
        routineWithoutOverlap.setSchedule(nonOverlappingSchedule);

        Schedule overlappingSchedule = new Schedule();
        overlappingSchedule.setDays(new HashSet<>(Set.of(WeekDay.Monday, WeekDay.Friday)));
        DiaryRoutine routineWithOverlap = new DiaryRoutine();
        routineWithOverlap.setSchedule(overlappingSchedule);

        DiaryRoutine routineForCreate = new DiaryRoutine();
        routineForCreate.setId(routineId);

        when(diaryRoutineService.getAllDiaryRoutinesModels(userId))
                .thenReturn(List.of(routineWithoutOverlap, routineWithOverlap));
        when(diaryRoutineService.getDiaryRoutineModelById(routineId, userId)).thenReturn(routineForCreate);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateScheduleDTO dto = new CreateScheduleDTO(newDays, routineId);
        scheduleService.create(dto, userId);

        assertEquals(Set.of(WeekDay.Friday), overlappingSchedule.getDays());
        verify(scheduleRepository, atLeast(2)).save(any(Schedule.class)); // one for overlap, one for new schedule
    }

    @Test
    void shouldIgnoreNullSchedulesWhenReplacingDays() {
        UUID userId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();
        Set<WeekDay> newDays = Set.of(WeekDay.Monday);

        DiaryRoutine routineWithNullSchedule = new DiaryRoutine();

        DiaryRoutine routineWithNullDays = new DiaryRoutine();
        Schedule scheduleWithNullDays = new Schedule();
        scheduleWithNullDays.setDays(null);
        routineWithNullDays.setSchedule(scheduleWithNullDays);

        Schedule overlappingSchedule = new Schedule();
        overlappingSchedule.setDays(new HashSet<>(Set.of(WeekDay.Monday, WeekDay.Tuesday)));
        DiaryRoutine routineWithOverlap = new DiaryRoutine();
        routineWithOverlap.setSchedule(overlappingSchedule);

        DiaryRoutine routineForCreate = new DiaryRoutine();
        routineForCreate.setId(routineId);

        when(diaryRoutineService.getAllDiaryRoutinesModels(userId))
                .thenReturn(List.of(routineWithNullSchedule, routineWithNullDays, routineWithOverlap));
        when(diaryRoutineService.getDiaryRoutineModelById(routineId, userId)).thenReturn(routineForCreate);
        when(scheduleRepository.save(any(Schedule.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateScheduleDTO dto = new CreateScheduleDTO(newDays, routineId);
        scheduleService.create(dto, userId);

        assertEquals(Set.of(WeekDay.Tuesday), overlappingSchedule.getDays());
        verify(scheduleRepository, atLeast(2)).save(any(Schedule.class));
    }
}
