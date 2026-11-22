package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One dated line of what Beyou remembers about one of the person's things: why a routine
 * started, what gets in the way of a habit, what changed about a goal.
 *
 * <p>A change adds a note instead of replacing one, so the notes of one thing read as a
 * timeline. {@link #subjectId} is a bare id with no foreign key (V47 says why); when the thing
 * is deleted, its notes move rather than go (see {@link SecondBrainHooks}).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(
    name = "second_brain_notes",
    indexes = @Index(name = "idx_second_brain_notes_user_subject", columnList = "user_id, subject_type, subject_id")
)
public class SecondBrainNote {

    /** Mirrors the varchar(500) in V47. */
    public static final int TEXT_MAX = 500;
    /** Mirrors the varchar(256) in V47. */
    public static final int SUBJECT_NAME_MAX = 256;
    /** Notes per thing. A 31st removes the oldest, so a thing's timeline keeps its latest turns. */
    public static final int MAX_PER_SUBJECT = 30;
    /**
     * Most notes one answer can leave on things. Like the fact caps, this bounds how much of a
     * prompt injection hidden in the person's data could persist and reach a later prompt.
     */
    public static final int LEARNED_PER_ANSWER = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** A bare id, like {@link SecondBrainFact#getUserId()}. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private SecondBrainSubjectType subjectType;

    /** The thing's id, or the category's for a moved note. Null only for a LOOSE note. */
    @Column(name = "subject_id")
    private UUID subjectId;

    /** The thing's name when the note was written, and its last name once it is gone. */
    @Column(name = "subject_name", nullable = false, length = SUBJECT_NAME_MAX)
    private String subjectName;

    /** What the thing was, set when its notes moved because it was deleted. */
    @Enumerated(EnumType.STRING)
    @Column(name = "former_type", length = 16)
    private SecondBrainSubjectType formerType;

    @Column(nullable = false, length = TEXT_MAX)
    @ToString.Exclude
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private SecondBrainNoteOrigin origin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
