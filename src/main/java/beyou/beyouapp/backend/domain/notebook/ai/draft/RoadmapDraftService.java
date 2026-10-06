package beyou.beyouapp.backend.domain.notebook.ai.draft;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.DraftChoiceDTO;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.RoadmapDraftRecordDTO;
import beyou.beyouapp.backend.domain.notebook.ai.draft.dto.RoadmapDraftSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.DraftNodeDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.monitoring.UserContextLogFilter;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Roadmap drafts that outlive the dialog.
 *
 * <p>"Draft" stores the request and answers at once with a DRAFTING draft. The model call runs
 * on a virtual thread after the request commits (the same after-commit handoff source ingestion
 * uses, so the job never looks for a row that is not there yet) and writes its result through
 * {@link RoadmapDraftWrites}. The person can close the dialog, do something else, and come back
 * to the draft from the notebook home: the form, the nodes and their ticks come back as they
 * were. A draft ends when the topic is created from it or the person deletes it.
 *
 * <p>One call per draft at a time: a draft that is DRAFTING refuses a redraft and refuses new
 * ticks, which belong to nodes that are about to be replaced.
 *
 * <p>{@code notebook.drafts.background=false} (the integration-test profile) stops the job from
 * starting on its own, so a test can run it with {@link #run} and assert on the result.
 */
@Service
@Slf4j
public class RoadmapDraftService {

    /** Drafts kept per person. Past this, the home would be a list of abandoned ideas. */
    public static final int MAX_DRAFTS = 20;

    private static final TypeReference<List<DraftChoiceDTO>> CHOICES = new TypeReference<>() {};

    private final NotebookRoadmapDraftRepository repository;
    private final RoadmapDraftWrites writes;
    private final NotebookAiService aiService;
    private final NotebookPageRepository pageRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final boolean background;
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();

    public RoadmapDraftService(NotebookRoadmapDraftRepository repository, RoadmapDraftWrites writes,
            NotebookAiService aiService, NotebookPageRepository pageRepository, UserRepository userRepository,
            ObjectMapper objectMapper, @Value("${notebook.drafts.background:true}") boolean background) {
        this.repository = repository;
        this.writes = writes;
        this.aiService = aiService;
        this.pageRepository = pageRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.background = background;
    }

    // ---------------------------------------------------------------- writes

    /** A new draft, DRAFTING, with the model call queued for when this commits. */
    @Transactional
    public RoadmapDraftRecordDTO start(User user, RoadmapDraftRequestDTO request) {
        if (repository.countByUserId(user.getId()) >= MAX_DRAFTS) {
            throw new BusinessException(ErrorKey.NOTEBOOK_DRAFT_LIMIT_REACHED, "Too many roadmap drafts");
        }
        Instant now = Instant.now();
        NotebookRoadmapDraft draft = new NotebookRoadmapDraft();
        draft.setUser(user);
        draft.setTitle(request.title().strip());
        draft.setStatus(RoadmapDraftStatus.DRAFTING);
        draft.setRequest(objectMapper.writeValueAsString(request));
        draft.setStartedAt(now);
        draft.setCreatedAt(now);
        draft.setUpdatedAt(now);
        repository.save(draft);
        schedule(draft.getId());
        return record(draft, false);
    }

    /**
     * Drafts again: from scratch, or applying {@code changeRequest} to {@code previous}. The
     * current nodes stay on the draft until the new ones arrive, so the dialog can show them.
     */
    @Transactional
    public RoadmapDraftRecordDTO redraft(User user, UUID draftId, RoadmapDraftRequestDTO request) {
        NotebookRoadmapDraft draft = owned(user, draftId);
        refuseWhileDrafting(draft);
        Instant now = Instant.now();
        draft.setTitle(request.title().strip());
        draft.setRequest(objectMapper.writeValueAsString(request));
        draft.setStatus(RoadmapDraftStatus.DRAFTING);
        draft.setErrorKey(null);
        draft.setStartedAt(now);
        draft.setUpdatedAt(now);
        schedule(draft.getId());
        return record(draft, false);
    }

    /** The ticks in the dialog. They must line up with the drafted nodes, one each. */
    @Transactional
    public RoadmapDraftRecordDTO saveChoices(User user, UUID draftId, List<DraftChoiceDTO> choices) {
        NotebookRoadmapDraft draft = owned(user, draftId);
        refuseWhileDrafting(draft);
        RoadmapDraftDTO result = result(draft);
        if (result == null || result.nodes().size() != choices.size()) {
            throw new IllegalArgumentException("One choice per drafted node");
        }
        draft.setChoices(objectMapper.writeValueAsString(choices));
        draft.setUpdatedAt(Instant.now());
        return record(draft, true);
    }

    @Transactional
    public void delete(User user, UUID draftId) {
        repository.delete(owned(user, draftId));
    }

    // ----------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public List<RoadmapDraftSummaryDTO> list(User user) {
        return repository.findByUserIdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(d -> {
                    RoadmapDraftDTO result = result(d);
                    return new RoadmapDraftSummaryDTO(d.getId(), d.getTitle(), d.getStatus(),
                            result == null ? 0 : result.nodes().size(), d.getErrorKey(), d.getStartedAt(), d.getUpdatedAt());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public RoadmapDraftRecordDTO get(User user, UUID draftId) {
        return record(owned(user, draftId), true);
    }

    /** Every draft the person has, for the account export. */
    @Transactional(readOnly = true)
    public List<RoadmapDraftRecordDTO> exportForUser(UUID userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(d -> record(d, false)).toList();
    }

    // ------------------------------------------------------------------- job

    private void schedule(UUID draftId) {
        if (!background) return;
        Runnable job = () -> jobs.submit(() -> run(draftId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    job.run();
                }
            });
        } else {
            job.run();
        }
    }

    /**
     * One model call for one draft. Public so the integration tests can run it on their own
     * thread; in production only {@link #schedule} calls it.
     */
    public void run(UUID draftId) {
        NotebookRoadmapDraft draft = repository.findById(draftId).orElse(null);
        if (draft == null || draft.getStatus() != RoadmapDraftStatus.DRAFTING) return;
        UUID userId = draft.getUser().getId();
        // Off the request thread, so the user id every log line carries is set by hand.
        MDC.put(UserContextLogFilter.USER_ID_KEY, userId.toString());
        try {
            User user = userRepository.findById(userId).orElse(null);
            if (user == null) return;
            RoadmapDraftRequestDTO request = objectMapper.readValue(draft.getRequest(), RoadmapDraftRequestDTO.class);
            RoadmapDraftDTO result = aiService.roadmapDraft(user, request);
            writes.ready(draftId, objectMapper.writeValueAsString(result));
        } catch (BusinessException e) {
            writes.failed(draftId, e.getErrorKey().name());
        } catch (RuntimeException e) {
            log.error("Roadmap draft {} failed", draftId, e);
            writes.failed(draftId, ErrorKey.AI_UNAVAILABLE.name());
        } finally {
            MDC.remove(UserContextLogFilter.USER_ID_KEY);
        }
    }

    /**
     * A restart ends every call in flight, and nothing will ever write their results. They are
     * marked FAILED so the person sees "try again" instead of a timer that never stops.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterrupted() {
        int count = writes.failInterrupted(ErrorKey.NOTEBOOK_DRAFT_INTERRUPTED.name());
        if (count > 0) {
            log.info("Marked {} roadmap draft(s) interrupted by the restart", count);
        }
    }

    @PreDestroy
    void shutdown() {
        jobs.shutdownNow();
    }

    // --------------------------------------------------------------- helpers

    private NotebookRoadmapDraft owned(User user, UUID draftId) {
        NotebookRoadmapDraft draft = repository.findById(draftId)
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_DRAFT_NOT_FOUND, "Roadmap draft not found"));
        if (!draft.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorKey.NOTEBOOK_DRAFT_NOT_OWNED, "Roadmap draft does not belong to the user");
        }
        return draft;
    }

    private static void refuseWhileDrafting(NotebookRoadmapDraft draft) {
        if (draft.getStatus() == RoadmapDraftStatus.DRAFTING) {
            throw new BusinessException(ErrorKey.NOTEBOOK_DRAFT_BUSY, "The draft is still being written");
        }
    }

    private RoadmapDraftDTO result(NotebookRoadmapDraft draft) {
        return draft.getResult() == null ? null : objectMapper.readValue(draft.getResult(), RoadmapDraftDTO.class);
    }

    /**
     * @param checkLinks drop a link offer whose page has been deleted since the draft was written,
     *                   so creating the topic does not fail on a page that is gone
     */
    private RoadmapDraftRecordDTO record(NotebookRoadmapDraft draft, boolean checkLinks) {
        RoadmapDraftDTO result = result(draft);
        if (checkLinks && result != null) {
            List<DraftNodeDTO> nodes = result.nodes().stream()
                    .map(n -> n.existingPageId() == null || pageRepository.existsById(n.existingPageId()) ? n
                            : new DraftNodeDTO(n.title(), n.why(), n.subtopics(), n.estimatedHours(), n.optional(),
                                    null, null, null))
                    .toList();
            result = new RoadmapDraftDTO(nodes, result.totalHours());
        }
        List<DraftChoiceDTO> choices = draft.getChoices() == null ? null
                : objectMapper.readValue(draft.getChoices(), CHOICES);
        return new RoadmapDraftRecordDTO(draft.getId(), draft.getTitle(), draft.getStatus(),
                objectMapper.readValue(draft.getRequest(), RoadmapDraftRequestDTO.class), result, choices,
                draft.getErrorKey(), draft.getStartedAt(), draft.getCreatedAt(), draft.getUpdatedAt());
    }
}
