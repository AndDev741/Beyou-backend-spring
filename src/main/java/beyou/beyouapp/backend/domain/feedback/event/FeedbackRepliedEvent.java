package beyou.beyouapp.backend.domain.feedback.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * R14/R15/KD4 — the admin has written a reply, and the reply is stored.
 *
 * This is the ONLY event that reaches the submitting user. There is
 * deliberately no status-change counterpart: moving a submission between
 * triage states publishes nothing, so no future status endpoint can email
 * anybody by accident.
 *
 * Published inside the storing transaction and consumed after it commits, so
 * a rolled-back reply never mails and a failing mail never costs the reply.
 */
@Getter
public class FeedbackRepliedEvent extends ApplicationEvent {

    private final UUID feedbackId;
    private final String recipientEmail;
    /** The recipient's stored language preference — resolved by the sender. */
    private final String recipientLanguage;
    /** What the user originally wrote, quoted back so the reply has context. */
    private final String originalBody;
    private final String replyBody;

    public FeedbackRepliedEvent(Object source,
                                UUID feedbackId,
                                String recipientEmail,
                                String recipientLanguage,
                                String originalBody,
                                String replyBody) {
        super(source);
        this.feedbackId = feedbackId;
        this.recipientEmail = recipientEmail;
        this.recipientLanguage = recipientLanguage;
        this.originalBody = originalBody;
        this.replyBody = replyBody;
    }
}
