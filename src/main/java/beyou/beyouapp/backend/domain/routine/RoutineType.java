package beyou.beyouapp.backend.domain.routine;

/**
 * Which shape a routine takes.
 *
 * <p>{@link #DAILY} is the original: sections with start and end times, every habit or task
 * inside a section carrying its own window. {@link #LIST} drops both — a flat, ordered list
 * of items the user checks whenever they like during the day.
 *
 * <p>The difference is deliberately confined to shape. Everything around a routine treats
 * the two identically: both are scheduled to weekdays through the same service, both reach
 * the dashboard on their days, both are snapshotted, both feed habit, routine and account
 * streaks, and both award XP through the same calculator. Nothing outside the routine
 * package should need to branch on this.
 *
 * <p>An enum column rather than a second {@code Routine} subclass. {@code routines} is a
 * SINGLE_TABLE hierarchy pinned to one {@code dtype}, and the whole check path
 * ({@code CheckItemService}, all four branches), the snapshot writer, the day-close
 * resolver and the schedule service are typed to {@code DiaryRoutine}. A subclass would
 * have meant reworking every one of them to gain nothing a field does not already give.
 *
 * <p>Mirrored by the {@code routines_routine_type_check} CHECK constraint in {@code V26}.
 * Adding a value here without adding it there makes every write of the new kind fail.
 */
public enum RoutineType {
    DAILY,
    LIST
}
