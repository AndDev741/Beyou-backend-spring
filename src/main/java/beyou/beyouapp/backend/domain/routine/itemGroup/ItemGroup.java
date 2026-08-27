package beyou.beyouapp.backend.domain.routine.itemGroup;

import java.time.LocalTime;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import jakarta.persistence.Column;
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
}
