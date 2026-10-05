package beyou.beyouapp.backend.domain.notebook.ai.draft;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * What the background job writes when a model call ends, each in a transaction of its own.
 *
 * <p>Only a row that is still DRAFTING is written. A draft deleted while the model worked stays
 * deleted, and a result never lands on a draft that has since moved on. Same shape as
 * {@code SourceWrites}.
 */
@Component
@RequiredArgsConstructor
public class RoadmapDraftWrites {

    private final NotebookRoadmapDraftRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean ready(UUID draftId, String resultJson) {
        return repository.findById(draftId)
                .filter(d -> d.getStatus() == RoadmapDraftStatus.DRAFTING)
                .map(draft -> {
                    draft.setStatus(RoadmapDraftStatus.READY);
                    draft.setResult(resultJson);
                    // New nodes, so the old ticks no longer line up with anything.
                    draft.setChoices(null);
                    draft.setErrorKey(null);
                    draft.setUpdatedAt(Instant.now());
                    return true;
                })
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(UUID draftId, String errorKey) {
        return repository.findById(draftId)
                .filter(d -> d.getStatus() == RoadmapDraftStatus.DRAFTING)
                .map(draft -> {
                    draft.setStatus(RoadmapDraftStatus.FAILED);
                    draft.setErrorKey(errorKey);
                    draft.setUpdatedAt(Instant.now());
                    return true;
                })
                .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int failInterrupted(String errorKey) {
        return repository.failInterrupted(errorKey, Instant.now());
    }
}
