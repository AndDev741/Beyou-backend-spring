package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminDetailDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminItemDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAttachmentDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackContextDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackReplyDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackResponseDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackSubmitterDTO;
import beyou.beyouapp.backend.user.User;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class FeedbackMapper {

    public Feedback toEntity(CreateFeedbackRequestDTO dto, User user) {
        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setCategory(dto.category());
        feedback.setBody(dto.body().trim());
        feedback.setStatus(FeedbackStatus.OPEN);
        feedback.setContext(toContext(dto.context()));
        return feedback;
    }

    public FeedbackResponseDTO toResponseDTO(Feedback feedback) {
        return new FeedbackResponseDTO(
                feedback.getId(),
                feedback.getCategory(),
                feedback.getBody(),
                toContextDTO(feedback.getContext()),
                feedback.getCreatedAt());
    }

    /**
     * R11/R12/KD4 — one row of the admin inbox, and the only place
     * {@link FeedbackStatus} crosses the wire. Keeping this mapping apart from
     * {@link #toResponseDTO(Feedback)} is what stops triage state leaking into
     * the submitter's own receipt.
     */
    public FeedbackAdminItemDTO toAdminItemDTO(Feedback feedback) {
        return new FeedbackAdminItemDTO(
                feedback.getId(),
                feedback.getCategory(),
                feedback.getStatus(),
                feedback.getBody(),
                toContextDTO(feedback.getContext()),
                toSubmitterDTO(feedback.getUser()),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt());
    }

    /** R12 — everything triage needs about one submission in a single read. */
    public FeedbackAdminDetailDTO toAdminDetailDTO(Feedback feedback,
                                                   List<FeedbackAttachment> attachments,
                                                   List<FeedbackReply> replies) {
        UUID feedbackId = feedback.getId();
        return new FeedbackAdminDetailDTO(
                feedbackId,
                feedback.getCategory(),
                feedback.getStatus(),
                feedback.getBody(),
                toContextDTO(feedback.getContext()),
                toSubmitterDTO(feedback.getUser()),
                attachments.stream().map(attachment -> toAttachmentDTO(attachment, feedbackId)).toList(),
                replies.stream().map(reply -> toReplyDTO(reply, feedbackId)).toList(),
                feedback.getCreatedAt(),
                feedback.getUpdatedAt());
    }

    /**
     * The one place the served-bytes URL is built. Both the upload response and
     * the admin detail view come through here, so the path can never drift from
     * the route that actually serves the bytes.
     */
    public FeedbackAttachmentDTO toAttachmentDTO(FeedbackAttachment attachment, UUID feedbackId) {
        return new FeedbackAttachmentDTO(
                attachment.getId(),
                feedbackId,
                "/feedback/" + feedbackId + "/attachments/" + attachment.getId(),
                FeedbackAttachmentStorageService.STORED_CONTENT_TYPE,
                attachment.getWidth(),
                attachment.getHeight(),
                attachment.getSizeBytes(),
                attachment.getCreatedAt());
    }

    public FeedbackReplyDTO toReplyDTO(FeedbackReply reply, UUID feedbackId) {
        return new FeedbackReplyDTO(
                reply.getId(),
                feedbackId,
                reply.getBody(),
                reply.getAuthor() == null ? null : reply.getAuthor().getName(),
                reply.getCreatedAt());
    }

    private FeedbackSubmitterDTO toSubmitterDTO(User user) {
        if (user == null) {
            return null;
        }
        return new FeedbackSubmitterDTO(user.getId(), user.getName(), user.getEmail());
    }

    private FeedbackContextDTO toContextDTO(FeedbackContext context) {
        FeedbackContext safe = context != null ? context : new FeedbackContext();
        return new FeedbackContextDTO(
                safe.getScreen(),
                safe.getAppVersion(),
                safe.getPlatform(),
                safe.getLanguage(),
                safe.getTheme());
    }

    private FeedbackContext toContext(FeedbackContextDTO dto) {
        FeedbackContext context = new FeedbackContext();
        if (dto == null) {
            return context;
        }
        context.setScreen(clamp(dto.screen(), FeedbackContext.SCREEN_MAX));
        context.setAppVersion(clamp(dto.appVersion(), FeedbackContext.APP_VERSION_MAX));
        context.setPlatform(clamp(dto.platform(), FeedbackContext.PLATFORM_MAX));
        context.setLanguage(clamp(dto.language(), FeedbackContext.LANGUAGE_MAX));
        context.setTheme(clamp(dto.theme(), FeedbackContext.THEME_MAX));
        return context;
    }

    /**
     * Belt-and-braces on top of the DTO's {@code @Size}: captured context is
     * machine-supplied, so a value that outgrows its column must be truncated
     * rather than cost the user their submission.
     */
    private String clamp(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
