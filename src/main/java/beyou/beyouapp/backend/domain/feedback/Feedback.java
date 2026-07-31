package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.user.User;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A single feedback submission (R2, R4, R6, R11).
 *
 * The owning user comes from the authenticated principal, never from the
 * request body. Schema lives in {@code V9__feedback.sql}; Hibernate runs
 * {@code ddl-auto: validate} in every environment, so this mapping and the
 * migration must agree exactly.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(name = "feedback", indexes = {
        @Index(name = "feedback_user_id_created_at_idx", columnList = "user_id, created_at"),
        @Index(name = "feedback_status_created_at_idx", columnList = "status, created_at")
})
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FeedbackCategory category;

    /** The user's free text. Length is bounded at the boundary DTO. */
    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** R11/KD4 — internal triage state, admin-only. Never mapped into the user-facing DTO. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private FeedbackStatus status = FeedbackStatus.OPEN;

    @Embedded
    private FeedbackContext context = new FeedbackContext();

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        setCreatedAt(now);
        setUpdatedAt(now);
        if (status == null) {
            setStatus(FeedbackStatus.OPEN);
        }
    }

    @PreUpdate
    public void preUpdate() {
        setUpdatedAt(LocalDateTime.now());
    }
}
