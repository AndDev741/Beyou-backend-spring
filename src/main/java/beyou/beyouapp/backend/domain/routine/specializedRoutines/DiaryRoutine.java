package beyou.beyouapp.backend.domain.routine.specializedRoutines;

import jakarta.persistence.CascadeType;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import beyou.beyouapp.backend.domain.routine.Routine;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;

/**
 * The persisted routine, in both of its shapes.
 *
 * <p>Still the only subclass, and still holding its items in sections, because a LIST
 * routine keeps exactly one section: unnamed for the user's purposes, with null start and
 * end times, created and maintained by {@code DiaryRoutineService}. That is what lets a
 * List routine be checked, skipped, snapshotted, scheduled and streaked by the code already
 * doing it for a Daily one, none of which had to learn a second shape exists.
 *
 * <p>The section is an internal representation and should stay one. Nothing outside this
 * package builds it, and nothing outside this package should reach through
 * {@code getRoutineSections().get(0)} to get at a list's items — use {@link #listSection()}
 * and {@link #listItems()}, which say what they mean and fail loudly when the invariant they
 * depend on has been broken.
 */
@Entity
@DiscriminatorValue("DiaryRoutine")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class DiaryRoutine extends Routine {

    @OneToMany(mappedBy = "routine", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<RoutineSection> routineSections  = new ArrayList<>();

    /**
     * The single section a LIST routine keeps its items in.
     *
     * @throws IllegalStateException if called on a DAILY routine, or on a LIST routine whose
     *         section is missing. Both mean a bug in this package rather than bad input:
     *         the service creates the section in the same transaction as the routine, so a
     *         list without one never reaches the database.
     */
    @Transient
    public RoutineSection listSection() {
        if (!isList()) {
            throw new IllegalStateException(
                    "listSection() is only meaningful for a LIST routine; this one is " + getRoutineType());
        }
        if (routineSections == null || routineSections.isEmpty()) {
            throw new IllegalStateException(
                    "LIST routine " + getId() + " has no section, which the service is supposed to guarantee");
        }
        return routineSections.get(0);
    }

    /**
     * A LIST routine's items, habits and tasks together, in the order the user arranged them.
     *
     * <p>One list rather than the two the section stores, because the order the user dragged
     * them into runs across both: a habit can sit between two tasks. The tie-break on id
     * keeps the order stable for rows that share a position, which only legacy rows can do
     * — {@code V26} defaults {@code order_index} to zero.
     */
    @Transient
    public List<ItemGroup> listItems() {
        RoutineSection section = listSection();
        List<HabitGroup> habits = section.getHabitGroups() == null ? List.of() : section.getHabitGroups();
        List<TaskGroup> tasks = section.getTaskGroups() == null ? List.of() : section.getTaskGroups();
        return Stream.<ItemGroup>concat(habits.stream(), tasks.stream())
                .sorted(Comparator.comparingInt(ItemGroup::getOrderIndex)
                        .thenComparing(group -> String.valueOf(group.getId())))
                .toList();
    }
}
