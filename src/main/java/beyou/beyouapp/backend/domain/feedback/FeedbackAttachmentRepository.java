package beyou.beyouapp.backend.domain.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeedbackAttachmentRepository extends JpaRepository<FeedbackAttachment, UUID> {

    long countByFeedbackId(UUID feedbackId);

    List<FeedbackAttachment> findAllByFeedbackIdOrderByCreatedAtAsc(UUID feedbackId);
}
