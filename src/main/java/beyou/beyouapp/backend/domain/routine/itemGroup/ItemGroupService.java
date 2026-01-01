package beyou.beyouapp.backend.domain.routine.itemGroup;

import java.util.UUID;

import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.exceptions.routine.DiaryRoutineNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class ItemGroupService {

    private final DiaryRoutineRepository diaryRoutineRepository;

    @ReadOnlyProperty
    public HabitGroup findHabitGroupByDTO(UUID habitGroupId) {
        DiaryRoutine routine = diaryRoutineRepository.findById(habitGroupId)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        return routine.getRoutineSections().stream()
                .flatMap(section -> section.getHabitGroups().stream())
                .filter(habitGroup -> habitGroup.getId()
                        .equals(habitGroupId))
                .findFirst()
                .orElse(null);
    }

    @ReadOnlyProperty
    public TaskGroup findTaskGroupByDTO(UUID taskGroupId) {
        DiaryRoutine routine = diaryRoutineRepository.findById(taskGroupId)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        return routine.getRoutineSections().stream()
                .flatMap(section -> section.getTaskGroups().stream())
                .filter(taskGroup -> taskGroup.getId().equals(taskGroupId))
                .findFirst()
                .orElse(null);
    }
}
