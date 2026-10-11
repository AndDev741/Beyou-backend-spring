package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A study cycle: an ordered queue of study blocks with no fixed weekday (see V39).
 *
 * <p>The row holds the configuration that is not a list (name, default mode, block size) and the
 * lap in progress. The week, the topics and the blocks are rows of their own, and the progress
 * of each block lives per lap in {@link StudyCycleLapBlock}.
 *
 * <p>{@code @DynamicUpdate} because the row has more than one writer: an edit of the name and a
 * credit that closes the lap can land in the same second, and a full-row UPDATE from either would
 * write back what the other changed. Every writer that moves the lap also takes the row's lock
 * first ({@link StudyCycleRepository#lockById}), so two credits cannot both close the same lap.
 */
@Entity
@Table(name = "study_cycles")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@ToString
public class StudyCycle {

    public static final int MAX_NAME_LENGTH = 80;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_mode", nullable = false, length = 8)
    private BlockMode defaultMode = BlockMode.GOAL;

    @Column(name = "max_block_minutes", nullable = false)
    private int maxBlockMinutes = 90;

    @Column(name = "lap_number", nullable = false)
    private int lapNumber = 1;

    @Column(name = "lap_started_at", nullable = false)
    private Instant lapStartedAt;

    @Column(nullable = false)
    private int position;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean archived() {
        return archivedAt != null;
    }
}
