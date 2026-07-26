package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackResponseDTO;
import beyou.beyouapp.backend.domain.feedback.event.FeedbackSubmittedEvent;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ApplicationEventPublisher eventPublisher;

    /**
     * R10/KD8 — persist the submission, then emit side effects.
     *
     * The acknowledgement (R13) hangs off the saved row: the event is
     * published here but delivered only after this transaction commits, so a
     * rolled-back submission never mails, and a mail failure — which happens
     * on another thread, after the commit — can never cost the user their
     * submission.
     */
    @Transactional
    public FeedbackResponseDTO submitFeedback(CreateFeedbackRequestDTO request, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFound("User not found when trying to submit feedback"));

        Feedback feedback = feedbackMapper.toEntity(request, user);

        Feedback saved;
        try {
            // Flushed here rather than at commit so a storage failure still
            // surfaces as FEEDBACK_CREATE_FAILED instead of an opaque commit error.
            saved = feedbackRepository.saveAndFlush(feedback);
        } catch (Exception e) {
            log.error("Error trying to save feedback for user {}", userId, e);
            throw new BusinessException(ErrorKey.FEEDBACK_CREATE_FAILED, "Error trying to submit the feedback");
        }

        log.info("Feedback {} submitted by user {} in category {}", saved.getId(), userId, saved.getCategory());

        eventPublisher.publishEvent(new FeedbackSubmittedEvent(
                this,
                saved.getId(),
                user.getEmail(),
                user.getLanguageInUse(),
                saved.getCategory(),
                saved.getBody()));

        return feedbackMapper.toResponseDTO(saved);
    }
}
