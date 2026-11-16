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
 * One question of the interview. Asked once, OPEN until the person answers or skips it, and
 * kept afterwards so the same thing is not asked again.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(
    name = "second_brain_questions",
    indexes = @Index(name = "idx_second_brain_questions_user_status", columnList = "user_id, status")
)
public class SecondBrainQuestion {

    /** Mirrors the varchar(300) in V45. */
    public static final int TEXT_MAX = 300;
    /** Mirrors the varchar(2000) in V45. */
    public static final int ANSWER_MAX = 2000;
    /** Questions waiting at once. More than a handful reads as a form, not a conversation. */
    public static final int MAX_OPEN = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** A bare id, like {@link SecondBrainFact#getUserId()}. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private SecondBrainQuestionKind kind;

    /** Stored in the language the account read Beyou in when it was asked. */
    @Column(nullable = false, length = TEXT_MAX, updatable = false)
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private SecondBrainQuestionStatus status = SecondBrainQuestionStatus.OPEN;

    @Column(length = ANSWER_MAX)
    @ToString.Exclude
    private String answer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Set when answered. A skipped question keeps it null. */
    @Column(name = "answered_at")
    private Instant answeredAt;
}
