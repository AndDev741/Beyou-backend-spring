package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentService;
import beyou.beyouapp.backend.domain.feedback.FeedbackAttachmentStorageService;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAttachmentDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Feedback submission for the authenticated user.
 *
 * The admin triage routes live under {@code /feedback/admin/**} (already
 * gated to ROLE_ADMIN in SecurityConfig) and are added by a later unit —
 * nothing here reads or lists submissions.
 */
@RestController
@RequestMapping("/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final FeedbackAttachmentService feedbackAttachmentService;
    private final AuthenticatedUser authenticatedUser;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponseDTO submitFeedback(@RequestBody @Valid CreateFeedbackRequestDTO request) {
        return feedbackService.submitFeedback(request, authenticatedUser.getAuthenticatedUser().getId());
    }

    /**
     * R3/R9 — attach one image to a submission the caller owns. One image per
     * request: the container's multipart cap is 6MB in total, so batching would
     * make a two-screenshot report fail for a reason the user cannot act on.
     */
    @PostMapping("/{feedbackId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackAttachmentDTO addAttachment(@PathVariable UUID feedbackId,
                                               @RequestParam("file") MultipartFile file) {
        return feedbackAttachmentService.addAttachment(
                feedbackId, authenticatedUser.getAuthenticatedUser(), file);
    }

    /**
     * Serves the stored bytes. Authenticated like any other route — the
     * owner-or-admin check lives in the service, because this path is NOT
     * under the ROLE_ADMIN-gated {@code /feedback/admin/**}.
     */
    @GetMapping("/{feedbackId}/attachments/{attachmentId}")
    public ResponseEntity<Resource> serveAttachment(@PathVariable UUID feedbackId,
                                                    @PathVariable UUID attachmentId) {
        Resource resource = feedbackAttachmentService.serveAttachment(
                feedbackId, attachmentId, authenticatedUser.getAuthenticatedUser());

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                // Private: the bytes are owner-or-admin only, so no shared cache
                // may hold them.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(resource);
    }
}
