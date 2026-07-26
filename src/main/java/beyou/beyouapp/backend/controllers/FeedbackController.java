package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackResponseDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    private final AuthenticatedUser authenticatedUser;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackResponseDTO submitFeedback(@RequestBody @Valid CreateFeedbackRequestDTO request) {
        return feedbackService.submitFeedback(request, authenticatedUser.getAuthenticatedUser().getId());
    }
}
