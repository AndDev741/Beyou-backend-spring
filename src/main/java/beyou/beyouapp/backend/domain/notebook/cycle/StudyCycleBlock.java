package beyou.beyouapp.backend.domain.notebook.cycle;

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
 * One block in a cycle's queue. Configuration only: what was studied in it lives per lap in
 * {@link StudyCycleLapBlock}, so re-planning can rewrite this row without touching the lap.
 *
 * <p>{@code pageId} is the chosen concept for a GOAL block whose source is PAGE, and an optional
 * hint for a TIME block. For GOAL + NEXT it stays null: the concept is whatever the roadmap says
 * is next when the block is reached.
 */
@Entity
@Table(name = "study_cycle_blocks")
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
public class StudyCycleBlock {

    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 600;
    public static final int MAX_NOTE_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    /** 1-based, the number the person sees ("bloco 7 de 15"). */
    @Column(nullable = false)
    private int position;

    @Column(name = "topic_id", nullable = false)
    private UUID topicId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private BlockMode mode;

    @Column(name = "planned_minutes", nullable = false)
    private int plannedMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "goal_source", length = 8)
    private GoalSource goalSource;

    @Column(name = "page_id")
    private UUID pageId;

    @Column(name = "require_quiz", nullable = false)
    private boolean requireQuiz;

    @Column(length = MAX_NOTE_LENGTH)
    private String note;

    public boolean goal() {
        return mode == BlockMode.GOAL;
    }
}
