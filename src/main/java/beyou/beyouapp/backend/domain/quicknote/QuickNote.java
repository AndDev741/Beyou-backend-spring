package beyou.beyouapp.backend.domain.quicknote;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One post-it on the Ideas board: a few lines of text, a colour, a spot and a stacking order.
 *
 * <p>The spot is the note's top-left corner in board units, the same units on every client, so
 * a note dropped on the web shows up in the same place on the phone. The server never moves a
 * note on its own except through "Tidy up" ({@link QuickNotePlacement#tidy}); a note created
 * without a spot gets the first free grid cell ({@link QuickNotePlacement#firstFreeCell}).
 *
 * <p>Archived is {@link #archivedAt} being set. An archived note keeps everything else, so a
 * restore puts it back where it was.
 *
 * <p>{@link #convertedTaskId} is a bare id rather than a relation: the note only needs to know
 * that it became a task, and the database nulls it when that task is deleted (V42, ON DELETE SET
 * NULL). A mapped {@code @ManyToOne} would drag the task, its categories and the task cache's
 * concerns into every board read.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(
    name = "quick_notes",
    indexes = @Index(name = "idx_quick_notes_user_archived", columnList = "user_id, archived_at")
)
public class QuickNote {

    /** Mirrors the varchar(500) in V42. */
    public static final int TEXT_MAX = 500;
    /** Active notes per account. Archived ones do not count. Keeps the board drawable and the agent's read bounded. */
    public static final int MAX_ACTIVE = 200;
    /** How far from the origin a note may sit, either way, on either axis. Mirrored by the CHECKs in V42. */
    public static final int COORD_LIMIT = 100_000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(nullable = false, length = TEXT_MAX)
    @ToString.Exclude
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QuickNoteColor color = QuickNoteColor.YELLOW;

    /** Top-left corner, board units. */
    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    /** Stacking order among the owner's notes: higher is drawn in front. */
    @Column(nullable = false)
    private int z;

    @Column(name = "archived_at")
    private Instant archivedAt;

    /** The task this note became, or null. Set once; a converted note cannot convert again. */
    @Column(name = "converted_task_id")
    private UUID convertedTaskId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isArchived() {
        return archivedAt != null;
    }
}
