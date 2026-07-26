package beyou.beyouapp.backend.domain.feedback;

import beyou.beyouapp.backend.domain.feedback.dto.CreateFeedbackRequestDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminDetailDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminItemDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackAdminPageDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackResponseDTO;
import beyou.beyouapp.backend.domain.feedback.dto.FeedbackStatusCountsDTO;
import beyou.beyouapp.backend.domain.feedback.event.FeedbackSubmittedEvent;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.exceptions.user.UserNotFound;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class FeedbackService {

    /** Bound on the user's free text (R6). Mirrored by the DTO's {@code @Size}. */
    public static final int BODY_MAX = 4000;

    /**
     * Admin listing page size. The default fills a console screen; the cap
     * exists so a hand-edited query string cannot ask the database for the
     * whole table in one go. A String because {@code @RequestParam} defaults
     * are String-typed.
     */
    public static final String DEFAULT_PAGE_SIZE = "20";
    public static final int MAX_PAGE_SIZE = 100;

    /** Newest submissions first — triage works from the top of the inbox. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

    private final FeedbackRepository feedbackRepository;
    private final FeedbackReplyRepository feedbackReplyRepository;
    private final FeedbackAttachmentRepository feedbackAttachmentRepository;
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

    /**
     * R12 — a page of the admin inbox, optionally narrowed to one triage state
     * and/or one category. Filtering happens in the database, so the totals
     * describe the filtered set rather than whatever happened to be loaded.
     *
     * Authorization is NOT enforced here: every caller arrives through
     * {@code /feedback/admin/**}, which SecurityConfig gates to ROLE_ADMIN.
     */
    @Transactional(readOnly = true)
    public FeedbackAdminPageDTO listForAdmin(FeedbackStatus status, FeedbackCategory category, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, NEWEST_FIRST);

        Page<Feedback> found;
        if (status != null && category != null) {
            found = feedbackRepository.findAllByStatusAndCategory(status, category, pageable);
        } else if (status != null) {
            found = feedbackRepository.findAllByStatus(status, pageable);
        } else if (category != null) {
            found = feedbackRepository.findAllByCategory(category, pageable);
        } else {
            found = feedbackRepository.findAll(pageable);
        }

        return new FeedbackAdminPageDTO(
                found.getContent().stream().map(feedbackMapper::toAdminItemDTO).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    /** R12 — one submission with its context, attachments and reply thread. */
    @Transactional(readOnly = true)
    public FeedbackAdminDetailDTO getForAdmin(UUID feedbackId) {
        Feedback feedback = requireFeedback(feedbackId);

        return feedbackMapper.toAdminDetailDTO(
                feedback,
                feedbackAttachmentRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedbackId),
                feedbackReplyRepository.findAllByFeedbackIdOrderByCreatedAtAsc(feedbackId));
    }

    /**
     * R12 — the console's headline numbers, counted in the database. A state
     * with no rows reports zero rather than being absent, so the console never
     * has to tell "none" apart from "missing".
     */
    @Transactional(readOnly = true)
    public FeedbackStatusCountsDTO countByStatusForAdmin() {
        Map<FeedbackStatus, Long> counts = new EnumMap<>(FeedbackStatus.class);
        feedbackRepository.countGroupedByStatus()
                .forEach(row -> counts.put(row.getStatus(), row.getTotal()));

        long open = counts.getOrDefault(FeedbackStatus.OPEN, 0L);
        long takingCare = counts.getOrDefault(FeedbackStatus.TAKING_CARE, 0L);
        long closed = counts.getOrDefault(FeedbackStatus.CLOSED, 0L);

        return new FeedbackStatusCountsDTO(open, takingCare, closed, open + takingCare + closed);
    }

    /**
     * R21 — the user's own feedback, shaped for the account data export.
     *
     * Everything they wrote and everything written back to them, newest first.
     * The query is keyed on the owner, so one user's export can never reach
     * another's submissions. Attachments are exported as references rather than
     * bytes — the URL comes from {@link FeedbackMapper#toAttachmentDTO}, the one
     * place that path is built, so an export can never point somewhere the
     * serving route does not.
     *
     * Returns plain maps rather than a DTO because that is the shape
     * {@code UserExportService} assembles the rest of the payload in.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportForUser(UUID userId) {
        return feedbackRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toExportMap)
                .toList();
    }

    private Map<String, Object> toExportMap(Feedback feedback) {
        UUID feedbackId = feedback.getId();

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", feedbackId);
        map.put("category", feedback.getCategory());
        map.put("body", feedback.getBody());
        map.put("status", feedback.getStatus());
        map.put("createdAt", feedback.getCreatedAt());
        map.put("updatedAt", feedback.getUpdatedAt());
        map.put("attachments", feedbackAttachmentRepository
                .findAllByFeedbackIdOrderByCreatedAtAsc(feedbackId).stream()
                .map(attachment -> feedbackMapper.toAttachmentDTO(attachment, feedbackId))
                .toList());
        map.put("replies", feedbackReplyRepository
                .findAllByFeedbackIdOrderByCreatedAtAsc(feedbackId).stream()
                .map(FeedbackService::toReplyExportMap)
                .toList());
        return map;
    }

    /**
     * A reply as its recipient's export sees it: what was said and when. The
     * author is deliberately left out — this is the submitter's data export,
     * not a directory of the staff who answered them.
     */
    private static Map<String, Object> toReplyExportMap(FeedbackReply reply) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", reply.getId());
        map.put("body", reply.getBody());
        map.put("createdAt", reply.getCreatedAt());
        return map;
    }

    /**
     * R11/R15/KD4 — move a submission between triage states.
     *
     * This method persists and nothing else. It publishes no event and there is
     * no status listener to receive one: a bare "closed" arriving in the user's
     * inbox with no message reads worse than silence, so only a written reply
     * speaks to them.
     */
    @Transactional
    public FeedbackAdminItemDTO updateStatusForAdmin(UUID feedbackId, FeedbackStatus status) {
        Feedback feedback = requireFeedback(feedbackId);

        FeedbackStatus previous = feedback.getStatus();
        feedback.setStatus(status);
        Feedback saved = feedbackRepository.saveAndFlush(feedback);

        log.info("Feedback {} moved from {} to {}", feedbackId, previous, status);

        return feedbackMapper.toAdminItemDTO(saved);
    }

    private Feedback requireFeedback(UUID feedbackId) {
        return feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new BusinessException(ErrorKey.FEEDBACK_NOT_FOUND, "Feedback not found"));
    }
}
