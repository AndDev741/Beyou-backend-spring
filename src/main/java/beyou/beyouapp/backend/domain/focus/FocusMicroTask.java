package beyou.beyouapp.backend.domain.focus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroup;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A small thing to do alongside one routine item.
 *
 * <p>Scoped to a routine ITEM, not to a sitting, which is the user's own specification and reverses
 * what shipped in F4: changing item does not carry the list over, unless the micro-task is pinned.
 *
 * <p><b>{@code pinned} is a template flag.</b> Selecting another item creates a row for that item
 * too, so a pinned "stretch" walked across four items leaves four rows, one per item, each
 * independently tickable. That is also what makes them show up per item in the day's snapshot.
 *
 * <p>The unique constraint is what makes materialising a template idempotent: the client can ask for
 * an item's list as often as it likes and the second ask creates nothing.
 */
@Entity
@Table(
    name = "focus_micro_tasks",
    uniqueConstraints = @UniqueConstraint(
        name = "focus_micro_tasks_unique_per_item",
        columnNames = {"user_id", "task_date", "item_group_id", "name"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FocusMicroTask {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The OWNER'S local day, for the same reason as everywhere else in this schema. */
    @Column(name = "task_date", nullable = false)
    private LocalDate taskDate;

    /**
     * NOT nullable, unlike the cycle's: a micro-task belongs to an item by definition now. The
     * column CASCADEs on delete, because a micro-task with no item is not a thing this model has.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "item_group_id", nullable = false)
    private ItemGroup itemGroup;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    /** When it was ticked. Null is open; a timestamp rather than a boolean, so "done" carries when. */
    @Column(name = "done_at")
    private Instant doneAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Position in this item's list, from zero.
     *
     * <p>Scoped to (user, day, item): each item's list is its own sequence. Rows written before
     * ordering existed all carry 0, which is why {@code created_at} stays the tiebreaker in the
     * queries — a list nobody has dragged still comes back in the order it was written.
     */
    @Column(name = "order_index", nullable = false)
    private int orderIndex;
}
