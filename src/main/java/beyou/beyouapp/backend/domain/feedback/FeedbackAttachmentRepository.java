package beyou.beyouapp.backend.domain.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachment, UUID> {

    long countByFeedbackId(UUID feedbackId);

    List<FeedbackAttachment> findAllByFeedbackIdOrderByCreatedAtAsc(UUID feedbackId);

    /**
     * Attachments for a set of submissions in one round trip, for the data export.
     *
     * <p>Ordered by submission first so the caller groups without re-sorting, then by
     * creation so a thread's images stay in the order they were added.
     */
    List<FeedbackAttachment> findAllByFeedbackIdInOrderByFeedbackIdAscCreatedAtAsc(
            Collection<UUID> feedbackIds);
}
