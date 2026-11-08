package beyou.beyouapp.backend.domain.routine.itemGroup;

import java.time.LocalTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.BatchSize;

import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "item_groups")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Inheritance(strategy = InheritanceType.JOINED)
public abstract class ItemGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private LocalTime startTime;

    private LocalTime endTime;

    @ManyToOne
    @JoinColumn(name = "routine_section_id")
    private RoutineSection routineSection;

    /**
     * Where this item sits in its section's list, counting from zero.
     *
     * <p>Only a LIST routine reads it. A DAILY one orders its items by {@link #startTime}
     * in both clients ({@code routineSection.tsx}, {@code sectionItems.ts}), which is the
     * ordering a timed routine wants and the one a list has no way to produce. The column is
     * still written for DAILY items, so the two shapes share one merge path instead of two.
     *
     * <p>{@code NOT NULL DEFAULT 0} in {@code V26}: rows predating the column all collapse to
     * position zero, which for a DAILY routine is read by nobody and for a LIST routine
     * cannot happen, since no LIST routine existed before that migration.
     */
    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    /**
     * The weekdays this item runs on, inside the days its routine runs on.
     *
     * <p>Empty means "every day the routine runs", which is what every item meant before
     * {@code V43} and what a client that has never heard of the field still gets. Whether an
     * item runs on a date is {@code ScheduledOnDayResolver.itemActiveOn}: read it there, not
     * here, so the rule stays in one place for the streak, the snapshot, the check path and
     * {@code GET /routine/today}.
     *
     * <p>Mapped like {@code Schedule.days}, with the same {@code @BatchSize}: every routine
     * read touches every item, and without the batch each item would cost its own query.
     */
    @ElementCollection(targetClass = WeekDay.class)
    @BatchSize(size = 50)
    @Enumerated(EnumType.STRING)
    @CollectionTable(name = "item_group_days", joinColumns = @JoinColumn(name = "item_group_id"))
    @Column(name = "days", nullable = false, columnDefinition = "varchar(20)")
    private Set<WeekDay> days = new HashSet<>();

    /**
     * Applies the days a client asked for.
     *
     * <p>Null leaves the item as it is. That is the whole contract for clients older than the
     * field: they send the routine back without it, and treating that as "no days" would wipe
     * the user's choice on every edit they make. An empty set clears the limit.
     *
     * <p>All seven days are stored as none, because they mean the same thing (the item runs
     * whenever the routine does) and two spellings of one rule is how clients start
     * disagreeing about it. The collection is changed in place: Hibernate tracks the one it
     * loaded, and swapping in a new set would turn every edit into a delete-all plus insert.
     */
    public void applyDays(Set<WeekDay> requested) {
        if (requested == null) {
            return;
        }
        Set<WeekDay> wanted = EnumSet.noneOf(WeekDay.class);
        requested.stream().filter(Objects::nonNull).forEach(wanted::add);
        if (wanted.size() == WeekDay.values().length) {
            wanted.clear();
        }
        if (days == null) {
            days = new HashSet<>();
        }
        days.retainAll(wanted);
        days.addAll(wanted);
    }
}
