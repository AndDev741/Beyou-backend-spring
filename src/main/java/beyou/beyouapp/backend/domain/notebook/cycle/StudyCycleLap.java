package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A closed lap, frozen at the moment its last block closed. Written once and never updated: the
 * numbers are stored rather than recomputed so an edit to the blocks later cannot rewrite the past.
 */
@Entity
@Table(name = "study_cycle_laps")
@Getter
@Setter
@NoArgsConstructor
public class StudyCycleLap {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(nullable = false)
    private int number;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "closed_at", nullable = false)
    private Instant closedAt;

    @Column(nullable = false)
    private int minutes;

    @Column(name = "planned_minutes", nullable = false)
    private int plannedMinutes;

    @Column(nullable = false)
    private int understood;

    @Column(name = "turns_used", nullable = false)
    private int turnsUsed;

    @Column(nullable = false)
    private int blocks;
}
