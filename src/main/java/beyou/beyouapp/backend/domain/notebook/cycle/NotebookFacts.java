package beyou.beyouapp.backend.domain.notebook.cycle;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.NotebookPage;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.ProgressGraph;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNodeRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.TargetDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyOutputRepository;

/**
 * What the notebook says about one user, read once per request and only as far as it is asked:
 * the page graph (titles, statuses, the roadmap's next leaf), the pages with a passed quiz, and
 * the cards due per topic. A credit on a TIME block never asks, so it never pays for the graph.
 *
 * <p>The graph is built from the repositories here rather than through
 * {@code NotebookProgressService}, because that service calls into the study cycle when pages
 * finish, and a cycle that called back into it would be a constructor loop.
 *
 * <p>Also the {@link StudyLap.Targets} every lap resolves GOAL blocks with:
 * <ul>
 *   <li>PAGE: the chosen page, unless it is already done (and its quiz passed, when the block
 *       asks for one). Then there is nothing left to understand and the block behaves like TIME.</li>
 *   <li>NEXT: {@link ProgressGraph#nextLeaf} of the topic, the first leaf being studied, else the
 *       first not started. Null when the roadmap is done ("roadmap done, review").</li>
 * </ul>
 */
final class NotebookFacts implements StudyLap.Targets {

    private final UUID userId;
    private final NotebookPageRepository pageRepository;
    private final NotebookBoardNodeRepository nodeRepository;
    private final NotebookStudyOutputRepository outputRepository;
    private final NotebookCardRepository cardRepository;

    private ProgressGraph graph;
    private Set<UUID> passedQuizzes;
    private Map<UUID, Integer> dueByTopic;

    NotebookFacts(UUID userId, NotebookPageRepository pageRepository, NotebookBoardNodeRepository nodeRepository,
            NotebookStudyOutputRepository outputRepository, NotebookCardRepository cardRepository) {
        this.userId = userId;
        this.pageRepository = pageRepository;
        this.nodeRepository = nodeRepository;
        this.outputRepository = outputRepository;
        this.cardRepository = cardRepository;
    }

    ProgressGraph graph() {
        if (graph == null) {
            graph = ProgressGraph.of(pageRepository.findByUserId(userId),
                    nodeRepository.findByUserIdAndKind(userId, NotebookNodeKind.PAGE));
        }
        return graph;
    }

    NotebookPage page(UUID pageId) {
        return pageId == null ? null : graph().page(pageId);
    }

    /** The topic a page belongs to (itself for a topic), or null for a page that is gone. */
    UUID rootOf(UUID pageId) {
        NotebookPage page = page(pageId);
        return page == null ? null : page.rootId();
    }

    boolean quizPassed(UUID pageId) {
        if (passedQuizzes == null) {
            passedQuizzes = outputRepository.findPassedQuizPages(userId);
        }
        return pageId != null && passedQuizzes.contains(pageId);
    }

    /** Cards due on {@code day} anywhere in the topic. */
    int cardsDue(UUID topicId, LocalDate day) {
        if (dueByTopic == null) {
            dueByTopic = new HashMap<>();
            for (Object[] row : cardRepository.countDueByPage(userId, day)) {
                UUID root = rootOf((UUID) row[0]);
                if (root != null) {
                    dueByTopic.merge(root, ((Number) row[1]).intValue(), Integer::sum);
                }
            }
        }
        return dueByTopic.getOrDefault(topicId, 0);
    }

    PageRefDTO ref(UUID pageId) {
        if (pageId == null) return null;
        NotebookPage page = page(pageId);
        return page == null ? new PageRefDTO(pageId, null, null) : new PageRefDTO(page.getId(), page.getTitle(), page.getIcon());
    }

    TargetDTO target(UUID pageId) {
        NotebookPage page = page(pageId);
        if (page == null) return null;
        return new TargetDTO(page.getId(), page.getTitle(), page.getIcon(), page.getStatus(),
                graph().progressOf(page.getId()));
    }

    @Override
    public UUID resolve(StudyCycleBlock block) {
        if (!block.goal()) return null;
        if (block.getGoalSource() == GoalSource.PAGE) {
            NotebookPage page = page(block.getPageId());
            if (page == null) return null;
            boolean understood = page.getStatus() == NotebookStatus.DONE
                    && (!block.isRequireQuiz() || quizPassed(page.getId()));
            return understood ? null : page.getId();
        }
        return graph().nextLeaf(block.getTopicId());
    }

    /** What the roadmap would give a NEXT block now, without fixing it. */
    UUID nextOf(UUID topicId) {
        return graph().nextLeaf(topicId);
    }
}
