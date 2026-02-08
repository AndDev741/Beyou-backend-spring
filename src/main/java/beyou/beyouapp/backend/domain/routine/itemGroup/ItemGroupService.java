package beyou.beyouapp.backend.domain.routine.itemGroup;

import java.util.UUID;

import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ItemGroupService {

    private final DiaryRoutineRepository diaryRoutineRepository;

    @ReadOnlyProperty
    public HabitGroup findHabitGroupByDTO(UUID routineId, UUID habitGroupId) {
        DiaryRoutine routine = diaryRoutineRepository.findById(routineId)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        return routine.getRoutineSections().stream()
                .flatMap(section -> section.getHabitGroups().stream())
                .filter(habitGroup -> habitGroup.getId()
                        .equals(habitGroupId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED, "Habit group not found in routine"));
    }

    @ReadOnlyProperty
    public TaskGroup findTaskGroupByDTO(UUID routineId,UUID taskGroupId) {
        DiaryRoutine routine = diaryRoutineRepository.findById(routineId)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        return routine.getRoutineSections().stream()
                .flatMap(section -> section.getTaskGroups().stream())
                .filter(taskGroup -> taskGroup.getId().equals(taskGroupId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED, "Task group not found in routine"));
    }
}
