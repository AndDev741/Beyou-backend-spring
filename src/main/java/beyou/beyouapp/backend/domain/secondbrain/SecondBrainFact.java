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
 * One line of the profile every chat should know: an objective, something the person likes,
 * or something about their life.
 *
 * <p>{@link #userId} is a bare id rather than a relation. Nothing here ever needs the account
 * itself, and the foreign key and its cascade live in V45.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(
    name = "second_brain_facts",
    indexes = @Index(name = "idx_second_brain_facts_user", columnList = "user_id")
)
public class SecondBrainFact {

    /*
     * The model calls behind this feature have no tools, so the most a prompt injection hidden
     * in the person's own data can do is change what gets saved as a fact. These caps, with
     * LEARNED_PER_PROFILE_ANSWER and LEARNED_PER_OTHER_ANSWER below, are the bound on how much
     * of that can persist and later reach a chat prompt.
     */

    /** Mirrors the varchar(200) in V45. */
    public static final int TEXT_MAX = 200;
    /** Facts per account, typed and learned together. */
    public static final int MAX_PER_USER = 20;
    /** Most facts one answer to a profile question can turn into. */
    public static final int LEARNED_PER_PROFILE_ANSWER = 3;
    /** Most lasting facts one check-in or follow-up answer can leave behind. */
    public static final int LEARNED_PER_OTHER_ANSWER = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SecondBrainFactKind kind;

    @Column(nullable = false, length = TEXT_MAX)
    @ToString.Exclude
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private SecondBrainFactOrigin origin;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
