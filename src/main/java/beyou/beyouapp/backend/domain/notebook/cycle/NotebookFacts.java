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
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SubjectRefDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.TargetDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyOutputRepository;

/**
 * What the notebook says about one user, read once per request and only as far as it is asked:
 * the page graph (titles, statuses, the roadmap's next leaf), the home tree (which subject a page
 * belongs to), the pages with a passed quiz, and the cards due per subject. A credit on a TIME
 * block only asks the home tree, which is ids and nothing else, so it never pays for the graph.
 *
 * <p>The graph is built from the repositories here rather than through
 * {@code NotebookProgressService}, because that service calls into the study cycle when pages
 * finish, and a cycle that called back into it would be a constructor loop.
 *
 * <p>Also the {@link StudyLap.Targets} every lap resolves GOAL blocks with:
 * <ul>
 *   <li>PAGE: the chosen page, unless it is already done (and its quiz passed, when the block
 *       asks for one). Then there is nothing left to understand and the block behaves like TIME.</li>
 *   <li>NEXT: {@link ProgressGraph#nextLeaf} of the subject, the first leaf being studied, else
 *       the first not started, on the subject's own board and the boards below it. Null when that
 *       roadmap is done ("roadmap done, review").</li>
 * </ul>
 */
final class NotebookFacts implements StudyLap.Targets {

    private final UUID userId;
    private final NotebookPageRepository pageRepository;
    private final NotebookBoardNodeRepository nodeRepository;
    private final NotebookStudyOutputRepository outputRepository;
    private final NotebookCardRepository cardRepository;

    private ProgressGraph graph;
    private HomeTree home;
    private Set<UUID> passedQuizzes;
    private Map<UUID, Integer> dueBelow;

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

    /**
     * Where every page lives. From the graph when a read already loaded it, else from a query
     * that reads ids only (a pomodoro credit asks this and nothing else).
     */
    HomeTree home() {
        if (home == null) {
            home = graph != null ? HomeTree.ofPages(graph.pages())
                    : HomeTree.ofLinks(pageRepository.findHomeLinks(userId));
        }
        return home;
    }

    boolean quizPassed(UUID pageId) {
        if (passedQuizzes == null) {
            passedQuizzes = outputRepository.findPassedQuizPages(userId);
        }
        return pageId != null && passedQuizzes.contains(pageId);
    }

    /**
     * Cards due on {@code day} on the subject and every page below it in the home tree. Each
     * page's count is added to every page on its home chain, so one pass answers for any subject,
     * a topic or a page inside one, and a linked page's cards count under its home only.
     */
    int cardsDue(UUID subjectId, LocalDate day) {
        if (dueBelow == null) {
            dueBelow = new HashMap<>();
            for (Object[] row : cardRepository.countDueByPage(userId, day)) {
                int count = ((Number) row[1]).intValue();
                for (UUID above : home().chain((UUID) row[0])) {
                    dueBelow.merge(above, count, Integer::sum);
                }
            }
        }
        return dueBelow.getOrDefault(subjectId, 0);
    }

    PageRefDTO ref(UUID pageId) {
        if (pageId == null) return null;
        NotebookPage page = page(pageId);
        return page == null ? new PageRefDTO(pageId, null, null) : new PageRefDTO(page.getId(), page.getTitle(), page.getIcon());
    }

    /** A subject, named, with the title of the topic it lives in when it is not one itself. */
    SubjectRefDTO subject(UUID pageId) {
        if (pageId == null) return null;
        NotebookPage page = page(pageId);
        if (page == null) return new SubjectRefDTO(pageId, null, null, null);
        NotebookPage root = page.isTopic() ? null : page(page.rootId());
        return new SubjectRefDTO(page.getId(), page.getTitle(), page.getIcon(), root == null ? null : root.getTitle());
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
    UUID nextOf(UUID subjectId) {
        return graph().nextLeaf(subjectId);
    }
}
