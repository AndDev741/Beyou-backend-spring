package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeedbackService {

    /** Bound on the user's free text (R6). Mirrored by the DTO's {@code @Size}. */
    public static final int BODY_MAX = 4000;

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final FeedbackMapper feedbackMapper;

    /**
     * R10/KD8 — persist the submission, then (from U4 onwards) emit side
     * effects. Notifications hang off the saved row: a failure to notify must
     * never cost the user their submission, so nothing that can fail
     * externally may run before {@code save} returns.
     */
    public FeedbackResponseDTO submitFeedback(CreateFeedbackRequestDTO request, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to submit feedback"));

        Feedback feedback = feedbackMapper.toEntity(request, user);

        Feedback saved;
        try {
            saved = feedbackRepository.save(feedback);
        } catch (Exception e) {
            log.error("Error trying to save feedback for user {}", userId, e);
            throw new BusinessException(ErrorKey.FEEDBACK_CREATE_FAILED, "Error trying to submit the feedback");
        }

        log.info("Feedback {} submitted by user {} in category {}", saved.getId(), userId, saved.getCategory());
        return feedbackMapper.toResponseDTO(saved);
    }
}
