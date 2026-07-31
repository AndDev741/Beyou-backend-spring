package beyou.beyouapp.backend.domain.feedback;

import org.springframework.util.StringUtils;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackReplyRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackReplyDTO;
import beyou.beyouapp.backend.domain.feedback.event.FeedbackRepliedEvent;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * R14/R15/KD4 — the admin writes back.
 *
 * This is the ONLY path that notifies a submitting user. A triage status
 * transition deliberately has no equivalent here: nothing about
 * {@link FeedbackStatus} publishes an event, so a status endpoint built on
 * top of this domain cannot accidentally email anybody.
 *
 * Authorization is NOT enforced here. The admin surface lives under
 * {@code /feedback/admin/**}, which SecurityConfig already gates to
 * ROLE_ADMIN; this service assumes the caller has passed that gate.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FeedbackReplyService {

    /** Bound on the admin's reply text. Mirrored by the DTO's {@code @Size}. */
    public static final int REPLY_BODY_MAX = 4000;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackReplyRepository feedbackReplyRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * R10/KD8 — store the reply, then let the notification hang off the
     * committed row. A mail failure must never cost a reply the admin
     * already wrote.
     */
    @Transactional
    public FeedbackReplyDTO reply(UUID feedbackId, UUID authorId, CreateFeedbackReplyRequestDTO request) {
        Feedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new BusinessException(
                        ErrorKey.FEEDBACK_NOT_FOUND, "Feedback not found when trying to reply"));

        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new BusinessException(
                        ErrorKey.FEEDBACK_NOT_FOUND, "Reply author not found"));

        FeedbackReply reply = new FeedbackReply();
        reply.setFeedback(feedback);
        reply.setAuthor(author);
        reply.setBody(request.body().trim());

        FeedbackReply saved;
        try {
            // Flushed here rather than at commit so a storage failure still
            // surfaces as FEEDBACK_REPLY_FAILED instead of an opaque commit error.
            saved = feedbackReplyRepository.saveAndFlush(reply);
        } catch (Exception e) {
            log.error("Error trying to save a reply to feedback {}", feedbackId, e);
            throw new BusinessException(ErrorKey.FEEDBACK_REPLY_FAILED, "Error trying to store the reply");
        }

        log.info("Reply {} written to feedback {} by user {}", saved.getId(), feedbackId, authorId);

        User recipient = feedback.getUser();
        eventPublisher.publishEvent(new FeedbackRepliedEvent(
                this,
                feedbackId,
                recipient.getEmail(),
                replyLanguage(feedback, recipient),
                feedback.getBody(),
                saved.getBody()));

        return toDTO(saved, feedbackId);
    }

    private FeedbackReplyDTO toDTO(FeedbackReply reply, UUID feedbackId) {
        return new FeedbackReplyDTO(
                reply.getId(),
                feedbackId,
                reply.getBody(),
                reply.getAuthor() == null ? null : reply.getAuthor().getName(),
                reply.getCreatedAt());
    }

    /**
     * A reply lands days after the submission, so the recipient's current
     * preference outranks whichever language they happened to be using when
     * they wrote. The submission context (R4) is the fallback for an account
     * that never set a preference.
     */
    private String replyLanguage(Feedback feedback, User recipient) {
        if (StringUtils.hasText(recipient.getLanguageInUse())) {
            return recipient.getLanguageInUse();
        }
        return feedback.getContext() == null ? null : feedback.getContext().getLanguage();
    }
}
