package beyou.beyouapp.backend.domain.notebook;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.focus.FocusCycleRepository;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardEdge;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardEdgeRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNode;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNodeRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookNodeKind;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardRepository;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardReviewRepository;
import beyou.beyouapp.backend.domain.notebook.card.ReviewStreak;
import beyou.beyouapp.backend.domain.notebook.dto.AppendRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.ContentSavedDTO;
import beyou.beyouapp.backend.domain.notebook.dto.ContinueDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreatePageRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.HomeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.LinkRefDTO;
import beyou.beyouapp.backend.domain.notebook.dto.MiniEdgeDTO;
import beyou.beyouapp.backend.domain.notebook.dto.MiniNodeDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageRefDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageSearchHitDTO;
import beyou.beyouapp.backend.domain.notebook.dto.ReviewSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.TopicDueDTO;
import beyou.beyouapp.backend.domain.notebook.dto.TopicLinksRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.TopicSummaryDTO;
import beyou.beyouapp.backend.domain.notebook.dto.TreeItemDTO;
import beyou.beyouapp.backend.domain.notebook.dto.TreeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdatePageRequestDTO;
import beyou.beyouapp.backend.domain.notebook.source.NotebookSourceRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Topics and pages: the tree, the documents, and the screens that summarise them.
 *
 * <p>Two rules live elsewhere on purpose. Ownership is {@link NotebookOwnership}, and status is
 * {@link NotebookProgressService}. This class only ever asks them.
 *
 * <p>Not cached, like mood: every read here is computed from the whole notebook graph, and a
 * cache keyed by user would need invalidating on every keystroke of the autosave.
 */
@Service
@RequiredArgsConstructor
public class NotebookPageService {

    private final NotebookPageRepository pageRepository;
    private final NotebookBoardNodeRepository nodeRepository;
    private final NotebookBoardEdgeRepository edgeRepository;
    private final NotebookCardRepository cardRepository;
    private final NotebookCardReviewRepository reviewRepository;
    private final NotebookSourceRepository sourceRepository;
    private final FocusCycleRepository focusCycleRepository;
    private final GoalRepository goalRepository;
    private final CategoryRepository categoryRepository;
    private final HabitRepository habitRepository;
    private final NotebookOwnership ownership;
    private final NotebookProgressService progressService;
    private final EntityManager entityManager;

    // ------------------------------------------------------------------ home

    @Transactional(readOnly = true)
    public HomeResponseDTO home(User user) {
        UUID userId = user.getId();
        ProgressGraph graph = progressService.graphFor(userId);
        LocalDate today = UserDateResolver.today(user);
        Map<UUID, Integer> dueByPage = counts(cardRepository.countDueByPage(userId, today));
        Map<UUID, Integer> sourcesByPage = counts(sourceRepository.countByPage(userId));

        List<NotebookPage> topics = graph.pages().stream()
                .filter(NotebookPage::isTopic)
                .sorted(Comparator.comparing(NotebookPage::getUpdatedAt).reversed())
                .toList();
        List<UUID> topicIds = topics.stream().map(NotebookPage::getId).toList();

        Map<UUID, List<NotebookBoardNode>> topicNodes = new HashMap<>();
        Map<UUID, List<NotebookBoardEdge>> topicEdges = new HashMap<>();
        if (!topicIds.isEmpty()) {
            nodeRepository.findByBoardPageIdIn(topicIds).forEach(
                    n -> topicNodes.computeIfAbsent(n.getBoardPageId(), k -> new ArrayList<>()).add(n));
            edgeRepository.findByBoardPageIdIn(topicIds).forEach(
                    e -> topicEdges.computeIfAbsent(e.getBoardPageId(), k -> new ArrayList<>()).add(e));
        }

        Map<UUID, Integer> dueByTopic = new HashMap<>();
        Map<UUID, Integer> sourcesByTopic = new HashMap<>();
        for (NotebookPage page : graph.pages()) {
            UUID root = page.rootId();
            dueByTopic.merge(root, dueByPage.getOrDefault(page.getId(), 0), Integer::sum);
            sourcesByTopic.merge(root, sourcesByPage.getOrDefault(page.getId(), 0), Integer::sum);
        }

        List<TopicSummaryDTO> summaries = new ArrayList<>();
        for (NotebookPage topic : topics) {
            UUID next = graph.nextLeaf(topic.getId());
            NotebookPage nextPage = next == null || next.equals(topic.getId()) ? null : graph.page(next);
            List<NotebookBoardNode> nodes = topicNodes.getOrDefault(topic.getId(), List.of()).stream()
                    .filter(n -> n.getKind() == NotebookNodeKind.PAGE)
                    .toList();
            List<MiniNodeDTO> preview = nodes.stream()
                    .map(n -> new MiniNodeDTO(n.getId(), n.getX(), n.getY(), statusOf(graph, n.getPageId())))
                    .toList();
            List<MiniEdgeDTO> previewEdges = topicEdges.getOrDefault(topic.getId(), List.of()).stream()
                    .map(e -> new MiniEdgeDTO(e.getSourceNodeId(), e.getTargetNodeId()))
                    .toList();
            summaries.add(new TopicSummaryDTO(
                    topic.getId(), topic.getTitle(), topic.getIcon(), topic.getDescription(),
                    graph.progressOf(topic.getId()),
                    dueByTopic.getOrDefault(topic.getId(), 0),
                    sourcesByTopic.getOrDefault(topic.getId(), 0),
                    nextPage == null ? null : ref(nextPage),
                    goalRef(topic.getGoal()), habitRef(topic.getHabit()),
                    preview, previewEdges, topic.getUpdatedAt()));
        }

        ContinueDTO continueStudying = graph.pages().stream()
                .filter(p -> p.getLastOpenedAt() != null)
                .max(Comparator.comparing(NotebookPage::getLastOpenedAt))
                .map(p -> {
                    NotebookPage topic = p.isTopic() ? p : graph.page(p.getTopicId());
                    UUID studying = graph.nextLeaf(p.getId());
                    NotebookPage studyingPage = studying == null || studying.equals(p.getId())
                            ? null : graph.page(studying);
                    return new ContinueDTO(p.getId(), p.getTitle(), p.getIcon(),
                            topic == null ? null : topic.getId(),
                            topic == null ? null : topic.getTitle(),
                            studyingPage == null ? null : studyingPage.getTitle(),
                            graph.progressOf(p.getId()), p.getLastOpenedAt());
                })
                .orElse(null);

        List<TopicDueDTO> byTopic = topics.stream()
                .map(t -> new TopicDueDTO(t.getId(), t.getTitle(), dueByTopic.getOrDefault(t.getId(), 0)))
                .filter(d -> d.due() > 0)
                .toList();
        int due = byTopic.stream().mapToInt(TopicDueDTO::due).sum();
        int streak = ReviewStreak.count(
                reviewRepository.reviewDaysSince(userId, today.minusDays(ReviewStreak.LOOKBACK_DAYS)), today);

        return new HomeResponseDTO(summaries, continueStudying, new ReviewSummaryDTO(due, byTopic, streak));
    }

    // ---------------------------------------------------------------- create

    @Transactional
    public PageResponseDTO createTopic(User user, CreateTopicRequestDTO request) {
        NotebookPage topic = NotebookPage.topic(user, request.title().strip(), Instant.now());
        topic.setDescription(blankToNull(request.description()));
        topic.setIcon(blankToNull(request.icon()));
        applyLinks(user.getId(), topic, request.goalId(), request.categoryId(), request.habitId());
        pageRepository.save(topic);
        return page(user, topic.getId(), false);
    }

    /** A page under a parent, NOT on its board. The board's own "Node" button goes through the board. */
    @Transactional
    public PageResponseDTO createPage(User user, CreatePageRequestDTO request) {
        NotebookPage parent = ownership.page(user.getId(), request.parentId());
        NotebookPage page = newChild(parent, request.title(), request.icon());
        return page(user, page.getId(), false);
    }

    /**
     * A child page of {@code parent}, saved, positioned after its siblings. Shared with the board,
     * which makes one for every new node.
     */
    @Transactional
    public NotebookPage newChild(NotebookPage parent, String title, String icon) {
        NotebookPage page = NotebookPage.childOf(parent, title.strip(), Instant.now());
        page.setIcon(blankToNull(icon));
        page.setPosition((int) pageRepository.countByParentId(parent.getId()));
        return pageRepository.save(page);
    }

    // ------------------------------------------------------------------ read

    /** The page screen. Opening a page also records it as the one to continue from. */
    @Transactional
    public PageResponseDTO open(User user, UUID pageId) {
        return page(user, pageId, true);
    }

    private PageResponseDTO page(User user, UUID pageId, boolean markOpened) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        if (markOpened) {
            page.setLastOpenedAt(Instant.now());
        }
        ProgressGraph graph = progressService.graphFor(user.getId());
        NotebookPage topic = page.isTopic() ? page : graph.page(page.getTopicId());

        List<PageRefDTO> breadcrumb = breadcrumb(graph, page);
        List<UUID> scope = breadcrumb.stream().map(PageRefDTO::id).toList();
        Set<UUID> subtree = ownedSubtree(graph, page.getId());

        LocalDate today = UserDateResolver.today(user);
        int sources = scope.isEmpty() ? 0
                : sourceRepository.findByPageIdInOrderByCreatedAtAsc(scope).size();
        long focus = focusCycleRepository.sumPomodoroMinutes(user.getId(), subtree);

        return new PageResponseDTO(
                page.getId(), page.getKind(), page.getTopicId(), page.getParentId(),
                page.getTitle(), page.getIcon(), page.getDescription(), page.getContent(),
                page.getStatus(), page.isStatusManual(), graph.hasBoard(page.getId()),
                graph.progressOf(page.getId()), breadcrumb,
                topic == null ? null : goalRef(topic.getGoal()),
                topic == null ? null : categoryRef(topic.getCategory()),
                topic == null ? null : habitRef(topic.getHabit()),
                (int) focus,
                (int) cardRepository.countByPageId(page.getId()),
                (int) cardRepository.countByPageIdAndDueOnLessThanEqual(page.getId(), today),
                sources,
                page.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public TreeResponseDTO tree(User user, UUID topicId) {
        NotebookPage topic = ownership.topic(user.getId(), topicId);
        ProgressGraph graph = progressService.graphFor(user.getId());
        List<NotebookPage> pages = pageRepository.findByTopicIdOrderByPositionAscCreatedAtAsc(topicId);
        LocalDate today = UserDateResolver.today(user);
        Map<UUID, Integer> dueByPage = counts(cardRepository.countDueByPage(user.getId(), today));
        Map<UUID, Integer> sourcesByPage = counts(sourceRepository.countByPage(user.getId()));

        List<TreeItemDTO> items = new ArrayList<>();
        Set<UUID> inTopic = new HashSet<>();
        inTopic.add(topicId);
        for (NotebookPage page : pages) {
            inTopic.add(page.getId());
        }
        for (NotebookPage page : pages) {
            boolean onBoard = graph.childrenOf(page.getParentId()).contains(page.getId());
            items.add(new TreeItemDTO(page.getId(), page.getParentId(), page.getTitle(), page.getIcon(),
                    page.getStatus(), onBoard, false, graph.progressOf(page.getId()),
                    boardPosition(graph, page)));
        }
        // Nodes on this topic's boards that open a page whose home is somewhere else.
        for (UUID boardId : new ArrayList<>(inTopic)) {
            List<UUID> kids = graph.childrenOf(boardId);
            for (int i = 0; i < kids.size(); i++) {
                UUID kid = kids.get(i);
                if (inTopic.contains(kid)) continue;
                NotebookPage linked = graph.page(kid);
                if (linked == null) continue;
                items.add(new TreeItemDTO(linked.getId(), boardId, linked.getTitle(), linked.getIcon(),
                        linked.getStatus(), true, true, graph.progressOf(linked.getId()), i));
            }
        }
        int due = 0;
        int sources = 0;
        for (UUID id : inTopic) {
            due += dueByPage.getOrDefault(id, 0);
            sources += sourcesByPage.getOrDefault(id, 0);
        }
        return new TreeResponseDTO(ref(topic), items, sources, due);
    }

    @Transactional(readOnly = true)
    public List<PageSearchHitDTO> search(User user, String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) return List.of();
        List<NotebookPage> hits = pageRepository
                .findTop20ByUserIdAndTitleContainingIgnoreCaseOrderByUpdatedAtDesc(user.getId(), q);
        Set<UUID> topicIds = new HashSet<>();
        hits.forEach(p -> topicIds.add(p.rootId()));
        Map<UUID, String> topicTitles = new HashMap<>();
        pageRepository.findByIdIn(topicIds).forEach(t -> topicTitles.put(t.getId(), t.getTitle()));
        return hits.stream()
                .map(p -> new PageSearchHitDTO(p.getId(), p.getTitle(), p.getIcon(), p.getKind(),
                        p.rootId(), topicTitles.get(p.rootId())))
                .toList();
    }

    // ---------------------------------------------------------------- update

    @Transactional
    public PageResponseDTO update(User user, UUID pageId, UpdatePageRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        if (request.title() != null) {
            page.setTitle(request.title().strip());
        }
        if (request.icon() != null) {
            page.setIcon(blankToNull(request.icon()));
        }
        if (request.description() != null) {
            page.setDescription(blankToNull(request.description()));
        }
        page.setUpdatedAt(Instant.now());
        return page(user, pageId, false);
    }

    /**
     * The autosave. Stores the document and re-derives the text the AI reads from it here, so
     * the two cannot disagree.
     */
    @Transactional
    public ContentSavedDTO saveContent(User user, UUID pageId, UpdateContentRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        page.setContent(request.content());
        page.setContentText(BlockText.extract(request.content()));
        Instant now = Instant.now();
        page.setUpdatedAt(now);
        return new ContentSavedDTO(page.getId(), now);
    }

    /** "Save to page": markdown from the study room, appended to the document as blocks. */
    @Transactional
    public PageResponseDTO append(User user, UUID pageId, AppendRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        appendMarkdown(page, request.markdown());
        return page(user, pageId, false);
    }

    /** Shared with the study room, which saves outputs straight into a page. */
    @Transactional
    public void appendMarkdown(NotebookPage page, String markdown) {
        String content = MarkdownBlocks.append(page.getContent(), markdown);
        page.setContent(content);
        page.setContentText(BlockText.extract(content));
        page.setUpdatedAt(Instant.now());
    }

    @Transactional
    public StatusChangeResponseDTO setStatus(User user, UUID pageId, SetStatusRequestDTO request) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        NotebookProgressService.Outcome outcome =
                progressService.setStatus(user.getId(), page.getId(), request.status());
        return new StatusChangeResponseDTO(page.getId(), page.getStatus(), page.isStatusManual(),
                outcome.changed(), outcome.xpEarned(), outcome.refreshUi());
    }

    @Transactional
    public PageResponseDTO setLinks(User user, UUID topicId, TopicLinksRequestDTO request) {
        NotebookPage topic = ownership.topic(user.getId(), topicId);
        applyLinks(user.getId(), topic, request.goalId(), request.categoryId(), request.habitId());
        topic.setUpdatedAt(Instant.now());
        return page(user, topicId, false);
    }

    // ---------------------------------------------------------------- delete

    /**
     * Deletes the page and everything under it in the tree (the foreign keys cascade). Boards
     * elsewhere that showed any of those pages through a link lose the node, and their status is
     * recomputed, because "all my nodes are done" may have just become true.
     */
    @Transactional
    public void delete(User user, UUID pageId) {
        NotebookPage page = ownership.page(user.getId(), pageId);
        ProgressGraph graph = progressService.graphFor(user.getId());
        Set<UUID> subtree = ownedSubtree(graph, page.getId());
        Set<UUID> affected = new LinkedHashSet<>();
        for (UUID id : subtree) {
            for (UUID board : graph.boardsShowing(id)) {
                if (!subtree.contains(board)) affected.add(board);
            }
        }
        pageRepository.delete(page);
        pageRepository.flush();
        // The database cascade removed the subtree, but this session still holds the rows it had
        // loaded for the graph. Left attached they would be served to any later read in this
        // request, and a dirty one would be flushed as an UPDATE of a row that no longer exists.
        for (UUID id : subtree) {
            NotebookPage gone = graph.page(id);
            if (gone != null && entityManager.contains(gone)) {
                entityManager.detach(gone);
            }
        }
        for (UUID board : affected) {
            progressService.boardChanged(user.getId(), board);
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * The page and every page whose home (parent chain) runs through it. Linked pages are NOT
     * included: they live in another topic, and deleting this page must not delete them.
     */
    Set<UUID> ownedSubtree(ProgressGraph graph, UUID pageId) {
        Map<UUID, List<UUID>> byParent = new HashMap<>();
        for (NotebookPage p : graph.pages()) {
            if (p.getParentId() != null) {
                byParent.computeIfAbsent(p.getParentId(), k -> new ArrayList<>()).add(p.getId());
            }
        }
        Set<UUID> subtree = new LinkedHashSet<>();
        Deque<UUID> stack = new ArrayDeque<>(List.of(pageId));
        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            if (!subtree.add(current)) continue;
            stack.addAll(byParent.getOrDefault(current, List.of()));
        }
        return subtree;
    }

    /** Topic first, this page last, by walking the parent chain. */
    public List<PageRefDTO> breadcrumb(ProgressGraph graph, NotebookPage page) {
        List<PageRefDTO> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        NotebookPage current = page;
        while (current != null && seen.add(current.getId())) {
            chain.add(ref(current));
            current = current.getParentId() == null ? null : graph.page(current.getParentId());
        }
        Collections.reverse(chain);
        return chain;
    }

    private int boardPosition(ProgressGraph graph, NotebookPage page) {
        int index = graph.childrenOf(page.getParentId()).indexOf(page.getId());
        // Off-board pages sort after the board, in the order they were made.
        return index >= 0 ? index : 1000 + page.getPosition();
    }

    private void applyLinks(UUID userId, NotebookPage topic, UUID goalId, UUID categoryId, UUID habitId) {
        topic.setGoal(goalId == null ? null : ownedGoal(userId, goalId));
        topic.setCategory(categoryId == null ? null : ownedCategory(userId, categoryId));
        topic.setHabit(habitId == null ? null : ownedHabit(userId, habitId));
    }

    private Goal ownedGoal(UUID userId, UUID goalId) {
        Goal goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new BusinessException(ErrorKey.GOAL_NOT_FOUND, "Goal not found"));
        if (!goal.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.GOAL_NOT_OWNED, "Goal does not belong to the user");
        }
        return goal;
    }

    private Category ownedCategory(UUID userId, UUID categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorKey.CATEGORY_NOT_FOUND, "Category not found"));
        if (!category.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.CATEGORY_NOT_OWNED, "Category does not belong to the user");
        }
        return category;
    }

    private Habit ownedHabit(UUID userId, UUID habitId) {
        Habit habit = habitRepository.findById(habitId)
                .orElseThrow(() -> new BusinessException(ErrorKey.HABIT_NOT_FOUND, "Habit not found"));
        if (!habit.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorKey.HABIT_NOT_OWNED, "Habit does not belong to the user");
        }
        return habit;
    }

    private static NotebookStatus statusOf(ProgressGraph graph, UUID pageId) {
        NotebookPage page = pageId == null ? null : graph.page(pageId);
        return page == null ? NotebookStatus.TO_STUDY : page.getStatus();
    }

    public static PageRefDTO ref(NotebookPage page) {
        return new PageRefDTO(page.getId(), page.getTitle(), page.getIcon());
    }

    private static LinkRefDTO goalRef(Goal goal) {
        return goal == null ? null : new LinkRefDTO(goal.getId(), goal.getName());
    }

    private static LinkRefDTO categoryRef(Category category) {
        return category == null ? null : new LinkRefDTO(category.getId(), category.getName());
    }

    private static LinkRefDTO habitRef(Habit habit) {
        return habit == null ? null : new LinkRefDTO(habit.getId(), habit.getName());
    }

    private static Map<UUID, Integer> counts(List<Object[]> rows) {
        Map<UUID, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return map;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
