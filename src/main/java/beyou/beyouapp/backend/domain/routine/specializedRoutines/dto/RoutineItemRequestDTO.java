package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;

/**
 * One entry in a LIST routine: a habit or a task, and nothing else.
 *
 * <p>No times, which is the whole point of the shape. No order either — position in the
 * request list IS the order, the same convention {@code mergeSections} already uses for
 * sections. An explicit index would only give a client a second place to say the same thing
 * and a way for the two to disagree.
 *
 * <p>{@code id} is the existing {@code ItemGroup} id on an update, so the merge can keep the
 * row and the check history hanging off it; null means a new entry. Exactly one of
 * {@code habitId} / {@code taskId} is set, which {@code DiaryRoutineService} enforces —
 * neither, or both, is a bad request rather than something to guess at.
 *
 * <p>{@code days} limits the entry to some of the days the routine runs on, with the same
 * rule as {@link HabitGroupDTO#days()}: null keeps what the entry has, empty clears it.
 */
public record RoutineItemRequestDTO(UUID id, UUID habitId, UUID taskId, Set<WeekDay> days) {

    /** {@code @JsonIgnore}: a derived getter, not a field a client sends. */
    @JsonIgnore
    public boolean isHabit() {
        return habitId != null;
    }

    @JsonIgnore
    public boolean isTask() {
        return taskId != null;
    }
}
