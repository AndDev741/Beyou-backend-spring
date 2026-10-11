package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One block in one lap: the minutes it got, the concept it resolved to, and how it closed.
 *
 * <p>Created lazily, the first time the lap credits, resolves or closes the block. A block with
 * no row in the current lap is OPEN with no minutes, which is what every block of a lap that just
 * started is.
 *
 * <p>{@code @DynamicUpdate}: a credit writes the minutes and a page finishing elsewhere writes
 * the state, and neither should write back what the other changed.
 */
@Entity
@Table(name = "study_cycle_lap_blocks")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class StudyCycleLapBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "block_id", nullable = false)
    private UUID blockId;

    @Column(name = "lap_number", nullable = false)
    private int lapNumber;

    @Column(nullable = false)
    private int minutes;

    @Column(name = "target_page_id")
    private UUID targetPageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private LapBlockState state = LapBlockState.OPEN;

    @Column(name = "closed_at")
    private Instant closedAt;

    public StudyCycleLapBlock(UUID cycleId, UUID blockId, int lapNumber) {
        this.cycleId = cycleId;
        this.blockId = blockId;
        this.lapNumber = lapNumber;
    }
}
