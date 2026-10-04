package beyou.beyouapp.backend.domain.notebook.card;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A flashcard on a page, with its spaced-repetition state.
 *
 * <p>{@code dueOn} is a day in the owner's timezone. Repetition works in days, and "due
 * tomorrow" has to mean the reader's tomorrow, so the state never stores an instant to be
 * converted later by somebody who forgot which zone it was in.
 */
@Entity
@Table(name = "notebook_cards")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotebookCard {

    /** Longest side the API accepts. The column is text; this is a validation limit. */
    public static final int MAX_SIDE_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "page_id", nullable = false)
    private UUID pageId;

    @Column(nullable = false, columnDefinition = "text")
    private String front;

    @Column(nullable = false, columnDefinition = "text")
    private String back;

    @Column(name = "source_label", length = 255)
    private String sourceLabel;

    @Column(name = "due_on", nullable = false)
    private LocalDate dueOn;

    @Column(name = "interval_days", nullable = false)
    private int intervalDays;

    @Column(nullable = false)
    private double ease = SpacedRepetition.START_EASE;

    @Column(nullable = false)
    private int reps;

    @Column(nullable = false)
    private int lapses;

    @Column(name = "last_reviewed_at")
    private Instant lastReviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public SpacedRepetition.State state() {
        return new SpacedRepetition.State(intervalDays, ease, reps, lapses);
    }

    public void apply(SpacedRepetition.State next, LocalDate today, Instant now) {
        this.intervalDays = next.intervalDays();
        this.ease = next.ease();
        this.reps = next.reps();
        this.lapses = next.lapses();
        this.dueOn = today.plusDays(next.intervalDays());
        this.lastReviewedAt = now;
        this.updatedAt = now;
    }
}
