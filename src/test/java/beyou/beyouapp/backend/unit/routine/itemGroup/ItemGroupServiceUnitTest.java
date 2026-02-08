package beyou.beyouapp.backend.unit.routine.itemGroup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;

@ExtendWith(MockitoExtension.class)
class ItemGroupServiceUnitTest {

    @Mock
    private DiaryRoutineRepository diaryRoutineRepository;

    @InjectMocks
    private ItemGroupService itemGroupService;

    @Test
    void findHabitGroupByDTO_shouldReturnHabitGroupWhenPresent() {
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(UUID.randomUUID());
        habitGroup.setHabit(new Habit());

        DiaryRoutine routine = routineWithGroups(habitGroup, null);
        when(diaryRoutineRepository.findById(habitGroup.getRoutineSection().getRoutine().getId())).thenReturn(Optional.of(routine));

        HabitGroup result = itemGroupService.findHabitGroupByDTO(habitGroup.getRoutineSection().getRoutine().getId(), habitGroup.getId());

        assertSame(habitGroup, result);
    }

    @Test
    void findHabitGroupByDTO_shouldReturnNullWhenNotFoundInRoutine() {
        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(UUID.randomUUID());
        habitGroup.setHabit(new Habit());

        HabitGroup otherGroup = new HabitGroup();
        otherGroup.setId(UUID.randomUUID());
        otherGroup.setHabit(new Habit());

        DiaryRoutine routine = routineWithGroups(otherGroup, null);
        when(diaryRoutineRepository.findById(routine.getId())).thenReturn(Optional.of(routine));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> itemGroupService.findHabitGroupByDTO(routine.getId(), habitGroup.getId()));
        assertEquals(ErrorKey.ITEM_GROUP_REQUIRED, exception.getErrorKey());
    }

    @Test
    void findHabitGroupByDTO_shouldThrowWhenRoutineNotFound() {
        UUID routineId = UUID.randomUUID();
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());

        assertThrows(DiaryRoutineNotFoundException.class,
                () -> itemGroupService.findHabitGroupByDTO(routineId, UUID.randomUUID()));
    }

    @Test
    void findTaskGroupByDTO_shouldReturnTaskGroupWhenPresent() {
        TaskGroup taskGroup = new TaskGroup();
        taskGroup.setId(UUID.randomUUID());
        taskGroup.setTask(new Task());

        DiaryRoutine routine = routineWithGroups(null, taskGroup);
        when(diaryRoutineRepository.findById(taskGroup.getRoutineSection().getRoutine().getId())).thenReturn(Optional.of(routine));

        TaskGroup result = itemGroupService.findTaskGroupByDTO(taskGroup.getRoutineSection().getRoutine().getId(), taskGroup.getId());

        assertSame(taskGroup, result);
    }

    @Test
    void findTaskGroupByDTO_shouldReturnNullWhenNotFoundInRoutine() {
        TaskGroup taskGroup = new TaskGroup();
        taskGroup.setId(UUID.randomUUID());
        taskGroup.setTask(new Task());

        TaskGroup otherGroup = new TaskGroup();
        otherGroup.setId(UUID.randomUUID());
        otherGroup.setTask(new Task());

        DiaryRoutine routine = routineWithGroups(null, otherGroup);
        when(diaryRoutineRepository.findById(routine.getId())).thenReturn(Optional.of(routine));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> itemGroupService.findTaskGroupByDTO(routine.getId(), taskGroup.getId()));
        assertEquals(ErrorKey.ITEM_GROUP_REQUIRED, exception.getErrorKey());
    }

    @Test
    void findTaskGroupByDTO_shouldThrowWhenRoutineNotFound() {
        UUID routineId = UUID.randomUUID();
        when(diaryRoutineRepository.findById(routineId)).thenReturn(Optional.empty());

        assertThrows(DiaryRoutineNotFoundException.class,
                () -> itemGroupService.findTaskGroupByDTO(routineId, UUID.randomUUID()));
    }

    private DiaryRoutine routineWithGroups(HabitGroup habitGroup, TaskGroup taskGroup) {
        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setHabitGroups(habitGroup != null ? new ArrayList<>(List.of(habitGroup)) : new ArrayList<>());
        section.setTaskGroups(taskGroup != null ? new ArrayList<>(List.of(taskGroup)) : new ArrayList<>());

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(UUID.randomUUID());
        routine.setRoutineSections(new ArrayList<>(List.of(section)));
        section.setRoutine(routine);

        if (habitGroup != null) {
            habitGroup.setRoutineSection(section);
        }
        if (taskGroup != null) {
            taskGroup.setRoutineSection(section);
        }

        return routine;
    }
}
