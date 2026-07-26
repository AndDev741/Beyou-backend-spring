package beyou.beyouapp.backend.domain.feedback;

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
 * One image attached to a feedback submission (R3, R9).
 *
 * The row is the index; the bytes live on disk under
 * {@code {upload-dir}/feedback-attachments/{feedbackId}/{id}.jpg}, written by
 * {@link FeedbackAttachmentStorageService}. Everything is re-encoded to JPEG on
 * the way in, so there is no stored content type to disagree with the file.
 *
 * Schema lives in {@code V10__feedback_attachment.sql}; Hibernate runs
 * {@code ddl-auto: validate} in every environment, so this mapping and the
 * migration must agree exactly.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(name = "feedback_attachment", indexes = {
        @Index(name = "feedback_attachment_feedback_id_created_at_idx", columnList = "feedback_id, created_at")
})
public class FeedbackAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne
    @JsonIgnore
    @JoinColumn(name = "feedback_id", nullable = false)
    @ToString.Exclude
    private Feedback feedback;

    /** Stored dimensions, after the downscale — what the admin will actually see. */
    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    /** Size of the stored JPEG, not of the upload. */
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        setCreatedAt(LocalDateTime.now());
    }
}
