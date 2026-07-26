package beyou.beyouapp.backend.domain.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeedbackReplyRepository extends JpaRepository<FeedbackReply, UUID> {

    /** A submission's reply thread, oldest first. */
    List<FeedbackReply> findAllByFeedbackIdOrderByCreatedAtAsc(UUID feedbackId);
}
