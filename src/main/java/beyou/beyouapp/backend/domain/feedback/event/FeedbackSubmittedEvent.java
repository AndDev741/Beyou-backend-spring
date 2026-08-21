package beyou.beyouapp.backend.domain.feedback.event;

import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.List;
import java.util.UUID;

/**
 * R13/KD8 — a submission has been stored.
 *
 * Published inside the storing transaction and consumed only after it commits,
 * so a rolled-back submission can never produce an acknowledgement, and a
 * failing acknowledgement can never roll back a submission.
 *
 * The event carries everything the mail needs (recipient, language, what they
 * wrote), so the listener never has to reach back into the database on another
 * thread. {@code adminRecipients} is here for that reason and no other: the
 * inbox alert goes to whoever holds ROLE_ADMIN, and resolving that list is the
 * sender's job, inside the transaction, not the listener's.
 */
@Getter
public class FeedbackSubmittedEvent extends ApplicationEvent {

    private final UUID feedbackId;
    private final String recipientEmail;
    /** The recipient's stored language preference — resolved by the sender. */
    private final String recipientLanguage;
    private final FeedbackCategory category;
    private final String body;
    /**
     * Who gets told a submission landed. Already excludes the submitter, so an
     * admin writing feedback is not alerted about their own message.
     */
    private final List<String> adminRecipients;

    public FeedbackSubmittedEvent(Object source,
                                  UUID feedbackId,
                                  String recipientEmail,
                                  String recipientLanguage,
                                  FeedbackCategory category,
                                  String body,
                                  List<String> adminRecipients) {
        super(source);
        this.feedbackId = feedbackId;
        this.recipientEmail = recipientEmail;
        this.recipientLanguage = recipientLanguage;
        this.category = category;
        this.body = body;
        this.adminRecipients = adminRecipients == null ? List.of() : List.copyOf(adminRecipients);
    }
}
