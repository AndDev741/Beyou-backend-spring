package beyou.beyouapp.backend.domain.notebook.cycle;

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
 * Study done outside the app, logged by hand ("Lançar sessão").
 *
 * <p>{@code studiedOn} only places it in the history. Its minutes go into the lap that is current
 * when it is logged, whatever that date says, and {@code cycleId} + {@code lapNumber} record
 * which lap that was, so deleting the session can take them back from that lap and no other.
 * Both are null when nothing was credited (the topic was in no active cycle, or had no block).
 */
@Entity
@Table(name = "study_sessions")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class StudySession {

    public static final int MAX_NOTE_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "topic_id", nullable = false)
    private UUID topicId;

    @Column(name = "page_id")
    private UUID pageId;

    @Column(nullable = false)
    private int minutes;

    @Column(name = "studied_on", nullable = false)
    private LocalDate studiedOn;

    @Column(length = MAX_NOTE_LENGTH)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cycle_id")
    private UUID cycleId;

    @Column(name = "lap_number")
    private Integer lapNumber;
}
