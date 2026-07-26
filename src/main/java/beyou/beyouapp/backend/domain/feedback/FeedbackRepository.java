package beyou.beyouapp.backend.domain.feedback;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeedbackRepository extends JpaRepository<Feedback, UUID> {
    List<Feedback> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
