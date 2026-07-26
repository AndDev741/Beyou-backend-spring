package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A written reply from the admin to a submission (R14).
 *
 * Writing one of these is the ONLY thing that notifies the submitter
 * (R15/KD4): moving the parent submission between triage states emits
 * nothing, because a bare "closed" arriving with no message reads worse
 * than silence.
 *
 * Schema lives in {@code V12__feedback_reply.sql}; Hibernate runs
 * {@code ddl-auto: validate} in every environment, so this mapping and the
 * migration must agree exactly.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(name = "feedback_reply", indexes = {
        @Index(name = "feedback_reply_feedback_id_created_at_idx", columnList = "feedback_id, created_at")
})
public class FeedbackReply {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "feedback_id", nullable = false)
    @ToString.Exclude
    private Feedback feedback;

    /**
     * The admin who wrote it. Nullable only after that account is removed —
     * the reply outlives its author so deleting an admin never erases what
     * other users were already told.
     */
    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "author_id")
    @ToString.Exclude
    private User author;

    /** The admin's free text. Length is bounded at the boundary DTO. */
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        setCreatedAt(LocalDateTime.now());
    }
}
