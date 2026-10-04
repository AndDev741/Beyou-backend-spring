package beyou.beyouapp.backend.domain.notebook;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNodeRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.domain.notebook.dto.PageStatusDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChoice;
import lombok.RequiredArgsConstructor;

/**
 * The only writer of {@code NotebookPage.status}, and the reason it is stored.
 *
 * <p>XP is paid when a page first reaches DONE, and a transition needs a "before". A status
 * computed on every read has no before, so it could never tell the moment a page finished from
 * the hundredth time somebody looked at a finished page.
 *
 * <p>The rules:
 * <ol>
 *   <li>A leaf (a page whose board has no nodes) has the status somebody gave it.</li>
 *   <li>A page with nodes follows them ({@link ProgressGraph#derivedStatus}) unless somebody set
 *       its status by hand. {@link StatusChoice#AUTO} hands it back to the nodes.</li>
 *   <li>Every change walks UP through every board that shows the page, recomputing each one
 *       that follows its nodes, until nothing changes. A page linked into two topics moves
 *       both.</li>
 *   <li>Reaching DONE pays {@link NotebookRewards#PAGE_DONE_XP} once per page, ever. Undoing
 *       and redoing does not pay again, so a status toggle cannot be farmed.</li>
 * </ol>
 *
 * <p>Every board change (a node added, removed or relinked) also comes through here, because
 * it changes what a page's nodes say.
 */
@Service
@RequiredArgsConstructor
public class NotebookProgressService {

    private final NotebookPageRepository pageRepository;
    private final NotebookBoardNodeRepository nodeRepository;
    private final NotebookRewards rewards;

    /** Everything the user has, as a graph. Two reads. */
    @Transactional(readOnly = true)
    public ProgressGraph graphFor(UUID userId) {
        return ProgressGraph.of(
                pageRepository.findByUserId(userId),
                nodeRepository.findByUserIdAndKind(userId, NotebookNodeKind.PAGE));
    }

    /** The person picked a status (or AUTO) for a page. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome setStatus(UUID userId, UUID pageId, StatusChoice choice) {
        ProgressGraph graph = graphFor(userId);
        NotebookPage page = graph.page(pageId);
        Outcome outcome = new Outcome();
        if (choice == StatusChoice.AUTO) {
            page.setStatusManual(false);
            NotebookStatus derived = graph.derivedStatus(pageId);
            if (derived != null) {
                apply(page, derived, outcome);
            }
        } else {
            // Manual only means something on a page whose nodes could otherwise overrule it. A
            // leaf's status is always the person's, so the flag stays off there and a node added
            // later takes over, which is what "Status moves to Done by itself" promises.
            page.setStatusManual(graph.hasBoard(pageId));
            apply(page, choice.toStatus(), outcome);
        }
        propagateUp(graph, pageId, outcome, new HashSet<>(Set.of(pageId)));
        return settle(page, outcome);
    }

    /**
     * A page's board changed: a node was added, removed or pointed somewhere else. Recompute
     * the page itself (if it follows its nodes) and everything above it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome boardChanged(UUID userId, UUID boardPageId) {
        ProgressGraph graph = graphFor(userId);
        NotebookPage page = graph.page(boardPageId);
        Outcome outcome = new Outcome();
        if (page == null) return outcome;
        if (!page.isStatusManual()) {
            NotebookStatus derived = graph.derivedStatus(boardPageId);
            if (derived != null) {
                apply(page, derived, outcome);
            }
        }
        propagateUp(graph, boardPageId, outcome, new HashSet<>(Set.of(boardPageId)));
        return settle(page, outcome);
    }

    private void propagateUp(ProgressGraph graph, UUID pageId, Outcome outcome, Set<UUID> visited) {
        for (UUID boardPageId : graph.boardsShowing(pageId)) {
            if (!visited.add(boardPageId)) continue;
            NotebookPage board = graph.page(boardPageId);
            // A page set by hand does not move, so nothing above it can move because of it.
            if (board == null || board.isStatusManual()) continue;
            NotebookStatus derived = graph.derivedStatus(boardPageId);
            if (derived == null || derived == board.getStatus()) continue;
            apply(board, derived, outcome);
            propagateUp(graph, boardPageId, outcome, visited);
        }
    }

    private void apply(NotebookPage page, NotebookStatus status, Outcome outcome) {
        if (page.getStatus() == status) return;
        Instant now = Instant.now();
        page.setStatus(status);
        page.setUpdatedAt(now);
        outcome.changed.add(new PageStatusDTO(page.getId(), status));
        if (status == NotebookStatus.DONE && page.getDoneXpAt() == null) {
            page.setDoneXpAt(now);
            outcome.finished.add(page);
        }
    }

    /** Pays for every page that finished in this change, in one go, and saves. */
    private Outcome settle(NotebookPage page, Outcome outcome) {
        if (!outcome.finished.isEmpty()) {
            NotebookRewards.Payroll payroll = new NotebookRewards.Payroll();
            for (NotebookPage finished : outcome.finished) {
                payroll.add(categoryOf(finished), NotebookRewards.PAGE_DONE_XP);
            }
            outcome.xpEarned = payroll.total();
            outcome.refreshUi = rewards.pay(page.getUser(), payroll);
        }
        return outcome;
    }

    /** The category XP goes to: the one on the page's topic. */
    private Category categoryOf(NotebookPage page) {
        if (page.isTopic()) return page.getCategory();
        return pageRepository.findById(page.getTopicId()).map(NotebookPage::getCategory).orElse(null);
    }

    /** What a status change did, for the response. */
    public static final class Outcome {
        private final List<PageStatusDTO> changed = new ArrayList<>();
        private final List<NotebookPage> finished = new ArrayList<>();
        private double xpEarned;
        private RefreshUiDTO refreshUi;

        public List<PageStatusDTO> changed() {
            return changed;
        }

        public double xpEarned() {
            return xpEarned;
        }

        public RefreshUiDTO refreshUi() {
            return refreshUi;
        }
    }
}
