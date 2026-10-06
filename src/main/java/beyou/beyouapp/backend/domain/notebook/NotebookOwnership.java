package beyou.beyouapp.backend.domain.notebook;

import java.util.UUID;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import lombok.RequiredArgsConstructor;

/**
 * The one ownership check for notebook pages.
 *
 * <p>Every notebook path (pages, boards, cards, sources, the study room, the AI, and the focus
 * cycle that names a page) loads a page through here before it does anything. A path that skipped
 * it would be an IDOR on the most personal writing in the product, so it is one method rather
 * than a pattern each service repeats.
 *
 * <p>Not-found and not-owned are separate keys, like every other domain, and both answer 400.
 */
@Component
@RequiredArgsConstructor
public class NotebookOwnership {

    private final NotebookPageRepository pageRepository;

    public NotebookPage page(UUID userId, UUID pageId) {
        NotebookPage page = pageRepository.findById(pageId)
                .orElseThrow(() -> new BusinessException(ErrorKey.NOTEBOOK_PAGE_NOT_FOUND,
                        "Notebook page not found"));
        if (!page.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED,
                    "Notebook page does not belong to the user");
        }
        return page;
    }

    /** Like {@link #page} but the page must be a topic. */
    public NotebookPage topic(UUID userId, UUID topicId) {
        NotebookPage page = page(userId, topicId);
        if (!page.isTopic()) {
            throw new BusinessException(ErrorKey.NOTEBOOK_TOPIC_REQUIRED, "Expected a topic");
        }
        return page;
    }
}
