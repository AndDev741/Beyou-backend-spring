package beyou.beyouapp.backend.domain.routine.itemGroup;

import java.util.Optional;
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
                .orElseThrow(() -> new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED,
                        habitGroupNotFoundMessage(routine, habitGroupId)));
    }

    @ReadOnlyProperty
    public TaskGroup findTaskGroupByDTO(UUID routineId,UUID taskGroupId) {
        DiaryRoutine routine = diaryRoutineRepository.findById(routineId)
                .orElseThrow(() -> new DiaryRoutineNotFoundException("Diary routine not found by id"));

        return routine.getRoutineSections().stream()
                .flatMap(section -> section.getTaskGroups().stream())
                .filter(taskGroup -> taskGroup.getId().equals(taskGroupId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorKey.ITEM_GROUP_REQUIRED,
                        taskGroupNotFoundMessage(routine, taskGroupId)));
    }

    /**
     * The AI agent kept sending the habit's own id here, because from the outside
     * both are "the id of the habit in my routine". They are not: the habit id names
     * the habit everywhere in the app, while a check happens on the habit's ENTRY in
     * one routine, which has an id of its own. The old message ("Habit group not
     * found in routine") gave the agent nothing to correct with, so it either gave
     * up or tried the same id again.
     *
     * When the id it sent is a habit that IS in this routine, the message hands back
     * the entry id it should have used. Users never see this text: the frontend
     * renders ITEM_GROUP_REQUIRED from its own translations.
     */
    private String habitGroupNotFoundMessage(DiaryRoutine routine, UUID sentId) {
        Optional<UUID> entryId = routine.getRoutineSections().stream()
                .flatMap(section -> section.getHabitGroups().stream())
                .filter(group -> group.getHabit() != null && sentId.equals(group.getHabit().getId()))
                .map(HabitGroup::getId)
                .findFirst();
        return entryId
                .map(id -> "That is the habit's own id, not its entry in this routine. "
                        + "Use habitGroupId " + id + " to check, skip or remove it here.")
                .orElse("Habit group not found in routine");
    }

    private String taskGroupNotFoundMessage(DiaryRoutine routine, UUID sentId) {
        Optional<UUID> entryId = routine.getRoutineSections().stream()
                .flatMap(section -> section.getTaskGroups().stream())
                .filter(group -> group.getTask() != null && sentId.equals(group.getTask().getId()))
                .map(TaskGroup::getId)
                .findFirst();
        return entryId
                .map(id -> "That is the task's own id, not its entry in this routine. "
                        + "Use taskGroupId " + id + " to check, skip or remove it here.")
                .orElse("Task group not found in routine");
    }
}
