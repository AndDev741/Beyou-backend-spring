package beyou.beyouapp.backend.domain.routine.schedule;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;

/**
 * Answers "was this owner scheduled on day D" for every kind of checkable owner.
 *
 * <p>Two callers need this and a third is coming: the snapshot scheduler decides which
 * routines to snapshot, and the day-close pass decides which outcome to stamp on an
 * entity that has no check row. Before this class the weekday computation lived inline
 * in the scheduler and the habit-to-routine walk lived in {@code HabitMapper}, so a
 * third copy was about to appear.
 *
 * <p>The traversal runs <em>down</em> from a routine list the caller already holds
 * rather than up from the item. Walking up would need a {@code TaskGroup} back-reference
 * that {@link beyou.beyouapp.backend.domain.task.Task} does not have, and would pay one
 * lazy collection load per item; the day-close pass loads the user's routines once and
 * asks about every owner against that same list.
 *
 * <p>Static rather than a bean, matching {@code CheckXpCalculator} and
 * {@code UserDateResolver}: it holds no state and needs no injection point.
 */
public final class ScheduledOnDayResolver {

    private ScheduledOnDayResolver() {}

    /**
     * Where an owner stands relative to a day's schedule.
     *
     * <p>A single boolean cannot carry this: the day-close pass writes {@code NOT_IN_ROUTINE}
     * when an entity belongs to no routine at all and {@code NOT_SCHEDULED} when it belongs
     * to one that does not cover the day. Those are different explanations for the same
     * grey square, and the row is permanent, so the distinction has to survive the call.
     */
    public record Standing(boolean inAnyRoutine, boolean scheduled) {

        /** Belongs to no routine, so scheduled nowhere. */
        public static final Standing ORPHANED = new Standing(false, false);
    }

    /** The {@link WeekDay} constant for a date. */
    public static WeekDay weekDayOf(LocalDate date) {
        return WeekDay.valueOf(date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
    }

    /**
     * Whether a routine's own schedule covers the date. A routine with no schedule, or a
     * schedule with no days, covers nothing — it exists but never runs.
     */
    public static boolean coversDay(Routine routine, LocalDate date) {
        if (routine == null || routine.getSchedule() == null) {
            return false;
        }
        Set<WeekDay> days = routine.getSchedule().getDays();
        return days != null && days.contains(weekDayOf(date));
    }

    /**
     * Where the given owner stands on the given day, judged against the routines supplied.
     *
     * <p>Membership is an OR across routines: a habit sitting in two routines is scheduled
     * when <em>either</em> covers the day. Deliberately not assuming one routine per weekday
     * — {@code ScheduleService.checkAndReplaceScheduledRoutines} only enforces that for
     * schedules written through it, and the rule here holds regardless.
     */
    public static Standing standingOf(CheckDayOwnerType ownerType, UUID ownerId,
                                      List<DiaryRoutine> routines, LocalDate date) {
        if (ownerType == null || routines == null || routines.isEmpty()) {
            return Standing.ORPHANED;
        }
        return switch (ownerType) {
            case USER -> new Standing(true, anyRoutineCovers(routines, date));
            case ROUTINE -> routineStanding(ownerId, routines, date);
            case HABIT -> itemStanding(ownerId, routines, date, true);
            case TASK -> itemStanding(ownerId, routines, date, false);
        };
    }

    private static boolean anyRoutineCovers(List<DiaryRoutine> routines, LocalDate date) {
        return routines.stream().anyMatch(routine -> coversDay(routine, date));
    }

    private static Standing routineStanding(UUID routineId, List<DiaryRoutine> routines, LocalDate date) {
        for (DiaryRoutine routine : routines) {
            if (routine != null && routine.getId() != null && routine.getId().equals(routineId)) {
                return new Standing(true, coversDay(routine, date));
            }
        }
        return Standing.ORPHANED;
    }

    /**
     * Walks every section of every routine looking for the item. {@code habitSide} picks
     * which group list to read; the two shapes are otherwise identical.
     */
    private static Standing itemStanding(UUID itemId, List<DiaryRoutine> routines,
                                         LocalDate date, boolean habitSide) {
        if (itemId == null) {
            return Standing.ORPHANED;
        }
        boolean found = false;
        boolean scheduled = false;

        for (DiaryRoutine routine : routines) {
            if (routine == null || routine.getRoutineSections() == null) {
                continue;
            }
            boolean inThisRoutine = false;
            for (RoutineSection section : routine.getRoutineSections()) {
                if (section == null) {
                    continue;
                }
                inThisRoutine = habitSide
                        ? sectionHoldsHabit(section, itemId)
                        : sectionHoldsTask(section, itemId);
                if (inThisRoutine) {
                    break;
                }
            }
            if (inThisRoutine) {
                found = true;
                scheduled |= coversDay(routine, date);
            }
        }
        return found ? new Standing(true, scheduled) : Standing.ORPHANED;
    }

    private static boolean sectionHoldsHabit(RoutineSection section, UUID habitId) {
        List<HabitGroup> groups = section.getHabitGroups();
        if (groups == null) {
            return false;
        }
        return groups.stream()
                .anyMatch(group -> group != null && group.getHabit() != null
                        && habitId.equals(group.getHabit().getId()));
    }

    private static boolean sectionHoldsTask(RoutineSection section, UUID taskId) {
        List<TaskGroup> groups = section.getTaskGroups();
        if (groups == null) {
            return false;
        }
        return groups.stream()
                .anyMatch(group -> group != null && group.getTask() != null
                        && taskId.equals(group.getTask().getId()));
    }
}
