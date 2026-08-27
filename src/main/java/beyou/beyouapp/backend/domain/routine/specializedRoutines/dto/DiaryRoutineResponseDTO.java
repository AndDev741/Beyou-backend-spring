package beyou.beyouapp.backend.domain.routine.specializedRoutines.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.RoutineType;
import beyou.beyouapp.backend.domain.routine.checks.BaseCheck;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.checks.TaskGroupCheck;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;

/**
 * A routine on the wire, in both shapes.
 *
 * <p>A LIST routine fills BOTH collections. {@code items} is the flat, ordered list its
 * clients render; {@code routineSections} carries the same items through the single internal
 * section they are stored in, so the snapshot writer, the check endpoints and every client
 * that has never heard of the List type keep reading what they already read. A DAILY routine
 * leaves {@code items} empty.
 *
 * <p>Duplicating the items across two fields is a deliberate trade: it costs a few hundred
 * bytes on one response shape and buys not having to touch the check path, the snapshot
 * serializer, or the mobile and web renderers that already walk sections.
 */
public record DiaryRoutineResponseDTO(
        UUID id,
        String name,
        String iconId,
        RoutineType type,
        List<RoutineSectionResponseDTO> routineSections,
        List<RoutineItemResponseDTO> items,
        ScheduleResponseDTO schedule,
        double xp,
        double actualLevelXp,
        double nextLevelXp,
        int level) {

    /**
     * The pre-List shape, for the tests and callers that predate the type and only ever
     * build DAILY routines. Safe here in a way it would not be on the request record: this
     * one is server-built and never deserialized, so no two-constructor ambiguity can reach
     * Jackson.
     */
    public DiaryRoutineResponseDTO(
            UUID id,
            String name,
            String iconId,
            List<RoutineSectionResponseDTO> routineSections,
            ScheduleResponseDTO schedule,
            double xp,
            double actualLevelXp,
            double nextLevelXp,
            int level) {
        this(id, name, iconId, RoutineType.DAILY, routineSections, List.of(),
                schedule, xp, actualLevelXp, nextLevelXp, level);
    }

    public record ScheduleResponseDTO(UUID id, Set<WeekDay> days) {}

    /** Which side of a {@link RoutineItemResponseDTO} is populated. */
    public enum RoutineItemType { HABIT, TASK }

    /**
     * One row of a LIST routine.
     *
     * <p>{@code id} is the {@code ItemGroup} id, which is what {@code POST /routine/check}
     * and {@code POST /routine/skip} already take — a list item is checked by exactly the
     * call a sectioned one is.
     *
     * <p>{@code checks} is typed to {@link BaseCheck} because habit and task checks are
     * mixed in one list here. Both subclasses add nothing but a {@code @JsonIgnore} back
     * reference, so the two serialize identically and no client can tell which it received.
     */
    public record RoutineItemResponseDTO(
            UUID id,
            RoutineItemType type,
            UUID habitId,
            UUID taskId,
            int orderIndex,
            List<BaseCheck> checks) {}

    public record RoutineSectionResponseDTO(
            UUID id,
            String name,
            String iconId,
            String startTime,
            String endTime,
            List<TaskGroupResponseDTO> taskGroup,
            List<HabitGroupResponseDTO> habitGroup,
            boolean favorite) {

        public record TaskGroupResponseDTO(
                UUID id,
                UUID taskId,
                String startTime,
                String endTime,
                List<TaskGroupCheck> taskGroupChecks) {
        }

        public record HabitGroupResponseDTO(
                UUID id,
                UUID habitId,
                String startTime,
                String endTime,
                List<HabitGroupCheck> habitGroupChecks) {
        }
    }
}
