package beyou.beyouapp.backend.domain.focus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.UuidGenerator;

import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroup;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One COMPLETED Focus Mode cycle.
 *
 * <p>One row per cycle rather than one per sitting, and that is the load-bearing decision. A sitting
 * has no reliable end: the app can be killed, the tab closed, the phone can die, and an "open
 * session" row would then need reconciling forever by something that has to guess. A completed cycle
 * is a fact that never needs closing, and "four pomodoros today" is a count over these rows.
 *
 * <p>Nothing is written for an abandoned cycle. The feature has no failure state by design, so there
 * is nothing to record and no tally to keep.
 */
@Entity
@Table(name = "focus_cycles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FocusCycle {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The OWNER'S local day, resolved from their timezone before the insert.
     *
     * <p>Not the server's. Every other dated row in this schema does the same, because a cycle
     * finished at 23:30 in Lisbon belongs to that Lisbon day whatever the server thinks.
     */
    @Column(name = "cycle_date", nullable = false)
    private LocalDate cycleDate;

    /**
     * The routine item this cycle was run on, or null.
     *
     * <p>Nullable on purpose: a cycle can run with nothing selected — an empty routine, or the whole
     * routine rather than one item. The column is {@code ON DELETE SET NULL}, because deleting a
     * routine must not erase the fact that somebody focused for 25 minutes that morning.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_group_id")
    private ItemGroup itemGroup;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private CycleKind kind;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    /** What it was set to run for, bounded 1..180 by the column's own CHECK. */
    @Column(name = "minutes", nullable = false)
    private int minutes;
}
