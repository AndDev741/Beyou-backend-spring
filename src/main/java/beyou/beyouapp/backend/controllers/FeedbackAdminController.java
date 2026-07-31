package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.domain.feedback.FeedbackCategory;
import beyou.beyouapp.backend.domain.feedback.FeedbackReplyService;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.feedback.FeedbackStatus;
import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackReplyRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminDetailDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminItemDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminPageDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackReplyDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackStatusCountsDTO;
import beyou.beyouapp.backend.domain.feedback.dto.UpdateFeedbackStatusRequestDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The admin triage console (R11, R12, R14, R15).
 *
 * KD5 — this is the single inbox. There is deliberately no integration with an
 * external tracker: the owner reads what came in and decides by hand what
 * graduates into a development ticket.
 *
 * Authorization is entirely path-based: every route here sits under
 * {@code /feedback/admin/**}, which SecurityConfig gates with
 * {@code hasRole("ADMIN")}. The project uses path rules only — do NOT add
 * method-level security annotations here, or the two mechanisms will drift and
 * the path rule will stop being the single answer to "who can reach this".
 */
@RestController
@RequestMapping("/feedback/admin")
@RequiredArgsConstructor
@Validated
public class FeedbackAdminController {

    private final FeedbackService feedbackService;
    private final FeedbackReplyService feedbackReplyService;
    private final AuthenticatedUser authenticatedUser;

    /**
     * R12 — the inbox, newest first, optionally narrowed to one triage state
     * and/or one category. Both filters are optional and independent.
     */
    @GetMapping("/items")
    public FeedbackAdminPageDTO listSubmissions(
            @RequestParam(required = false) FeedbackStatus status,
            @RequestParam(required = false) FeedbackCategory category,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = FeedbackService.DEFAULT_PAGE_SIZE)
            @Min(1) @Max(FeedbackService.MAX_PAGE_SIZE) int size) {
        return feedbackService.listForAdmin(status, category, page, size);
    }

    /**
     * R12 — the console's headline numbers. Unfiltered by design: these are the
     * inbox tabs, not a summary of the current listing filter.
     */
    @GetMapping("/counts")
    public FeedbackStatusCountsDTO countSubmissions() {
        return feedbackService.countByStatusForAdmin();
    }

    /** R12 — one submission with its context, its attachments and its reply thread. */
    @GetMapping("/items/{feedbackId}")
    public FeedbackAdminDetailDTO getSubmission(@PathVariable UUID feedbackId) {
        return feedbackService.getForAdmin(feedbackId);
    }

    /**
     * R11/R15/KD4 — re-status a submission. Persists and stops there: no event,
     * no listener, no mail. Talking to the user is what the reply route below
     * is for, and keeping the two apart is what makes the silence structural
     * rather than a promise.
     */
    @PutMapping("/items/{feedbackId}/status")
    public FeedbackAdminItemDTO updateStatus(@PathVariable UUID feedbackId,
                                             @RequestBody @Valid UpdateFeedbackStatusRequestDTO request) {
        return feedbackService.updateStatusForAdmin(feedbackId, request.status());
    }

    /**
     * R14 — write back to the submitter. The acting admin is taken from the
     * authenticated principal, never from the request body, and the mail is
     * fired by the reply service once the row is committed.
     */
    @PostMapping("/items/{feedbackId}/replies")
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackReplyDTO reply(@PathVariable UUID feedbackId,
                                  @RequestBody @Valid CreateFeedbackReplyRequestDTO request) {
        return feedbackReplyService.reply(
                feedbackId, authenticatedUser.getAuthenticatedUser().getId(), request);
    }
}
