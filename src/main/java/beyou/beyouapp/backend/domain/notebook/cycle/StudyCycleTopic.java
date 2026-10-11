package beyou.beyouapp.backend.domain.notebook.cycle;

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
 * A notebook topic inside a cycle, with the two numbers the planner weighs it by.
 *
 * <p>{@code active} goes false when the cycle is archived. The partial unique index on
 * {@code topic_id WHERE active} (V39) is what keeps a topic in one active cycle at a time.
 */
@Entity
@Table(name = "study_cycle_topics")
@Getter
@Setter
@NoArgsConstructor
public class StudyCycleTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "cycle_id", nullable = false)
    private UUID cycleId;

    @Column(name = "topic_id", nullable = false)
    private UUID topicId;

    /** 1..5: how much the subject weighs (in the exam, in the course). */
    @Column(nullable = false)
    private int importance;

    /** 1..5: how well the person already knows it. More mastery, fewer minutes. */
    @Column(nullable = false)
    private int mastery;

    @Column(name = "minutes_override")
    private Integer minutesOverride;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int position;
}
