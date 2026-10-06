package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.focus.CycleKind;
import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.RecordCycleRequestDTO;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookRewards;
import beyou.beyouapp.backend.domain.notebook.NotebookStatus;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardNodeRepository;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateEdgeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.CardRating;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardService;
import beyou.beyouapp.backend.domain.notebook.card.dto.CardDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.CreateCardRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.FinishReviewResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreatePageRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChangeResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChoice;
import beyou.beyouapp.backend.domain.notebook.dto.TreeItemDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The study notebook against a real Postgres.
 *
 * <p>Real rather than mocked because the rules under test cross tables the database enforces:
 * the cascade that takes a subtree, the unique node per board, the shape CHECK that keeps a topic
 * a root, and the transaction the XP payment has to join. Each test names the rule it guards.
 */
@Transactional
class NotebookServiceIT extends AbstractIntegrationTest {

    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private NotebookCardService cardService;
    @Autowired private NotebookPageRepository pageRepository;
    @Autowired private NotebookBoardNodeRepository nodeRepository;
    @Autowired private FocusService focusService;
    @Autowired private CategoryService categoryService;
    @Autowired private UserRepository userRepository;

    private User user;
    private User stranger;

    @BeforeEach
    void seed() {
        user = newUser("notebook-owner");
        stranger = newUser("notebook-stranger");
    }

    // ------------------------------------------------------------ tree and board

    @Test
    void aNewNodeIsANewChildPageOnTheBoard() {
        PageResponseDTO topic = topic("Software Engineering");

        BoardChangeResponseDTO added = node(topic.id(), "Data Structures", 0, 0);

        assertThat(added.node().pageId()).isNotNull();
        assertThat(added.node().title()).isEqualTo("Data Structures");
        assertThat(added.node().linked()).isFalse();
        var page = pageRepository.findById(added.node().pageId()).orElseThrow();
        assertThat(page.getParentId()).isEqualTo(topic.id());
        assertThat(page.getTopicId()).isEqualTo(topic.id());
    }

    /** The sidebar splits a page's children into "on the board" and "pages off the board". */
    @Test
    void theTreeTellsBoardPagesFromLoosePages() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID onBoard = node(topic.id(), "Algorithms", 0, 0).node().pageId();
        PageResponseDTO loose = pageService.createPage(user,
                new CreatePageRequestDTO(topic.id(), "Interview questions log", null));

        List<TreeItemDTO> items = pageService.tree(user, topic.id()).items();

        assertThat(items).filteredOn(i -> i.id().equals(onBoard)).singleElement()
                .satisfies(i -> assertThat(i.onBoard()).isTrue());
        assertThat(items).filteredOn(i -> i.id().equals(loose.id())).singleElement()
                .satisfies(i -> assertThat(i.onBoard()).isFalse());
    }

    /**
     * Progress counts leaves through nested boards: a node with a board of its own counts as what
     * is on that board, not as one. Two leaves on the topic's board, one of them a page with two
     * leaves under it, is three leaves.
     */
    @Test
    void progressCountsLeavesThroughNestedBoards() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID basics = node(topic.id(), "Programming Basics", 0, 0).node().pageId();
        UUID structures = node(topic.id(), "Data Structures", 240, 0).node().pageId();
        UUID arrays = node(structures, "Arrays", 0, 0).node().pageId();
        node(structures, "Trees", 240, 0);

        status(arrays, StatusChoice.DONE);
        status(basics, StatusChoice.DONE);

        PageResponseDTO read = pageService.open(user, topic.id());
        assertThat(read.progress().total()).isEqualTo(3);
        assertThat(read.progress().done()).isEqualTo(2);
    }

    /**
     * The rollup: when every node on a page's board is done the page is done, and that moves the
     * topic above it. XP is paid for each page that finishes, the derived ones included.
     */
    @Test
    void finishingTheLastNodeFinishesItsPageAndPaysForBoth() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID structures = node(topic.id(), "Data Structures", 0, 0).node().pageId();
        node(topic.id(), "Algorithms", 240, 0);
        UUID arrays = node(structures, "Arrays", 0, 0).node().pageId();
        UUID trees = node(structures, "Trees", 240, 0).node().pageId();

        StatusChangeResponseDTO first = status(arrays, StatusChoice.DONE);
        assertThat(statusOf(structures)).isEqualTo(NotebookStatus.STUDYING);
        assertThat(first.xpEarned()).isEqualTo(NotebookRewards.PAGE_DONE_XP);

        StatusChangeResponseDTO last = status(trees, StatusChoice.DONE);

        assertThat(statusOf(structures)).isEqualTo(NotebookStatus.DONE);
        // Algorithms is untouched, so the topic is studying, not done.
        assertThat(statusOf(topic.id())).isEqualTo(NotebookStatus.STUDYING);
        assertThat(last.xpEarned()).isEqualTo(2 * NotebookRewards.PAGE_DONE_XP);
        assertThat(last.changed()).extracting(c -> c.pageId()).contains(trees, structures);
        assertThat(last.refreshUi()).isNotNull();
    }

    /** Toggling a page done, undone and done again pays once. Otherwise the status is an XP tap. */
    @Test
    void aPageIsPaidForOnceEver() {
        PageResponseDTO topic = topic("English C1");
        UUID lesson = node(topic.id(), "Phrasal verbs", 0, 0).node().pageId();
        // Two leaves, so finishing one does not also finish the topic and blur the count.
        node(topic.id(), "Idioms", 240, 0);

        double xpBefore = userXp();
        status(lesson, StatusChoice.DONE);
        status(lesson, StatusChoice.TO_STUDY);
        StatusChangeResponseDTO again = status(lesson, StatusChoice.DONE);

        assertThat(again.xpEarned()).isZero();
        assertThat(userXp() - xpBefore).isEqualTo(NotebookRewards.PAGE_DONE_XP);
    }

    @Test
    void xpGoesToTheTopicsCategory() {
        Category career = categoryService.createCategoryEntity(
                new CategoryRequestDTO("Career", "ic", null, ExperienceLevel.BEGINNER), user);
        PageResponseDTO topic = pageService.createTopic(user,
                new CreateTopicRequestDTO("Software Engineering", null, null, null, career.getId(), null));
        UUID node = node(topic.id(), "Hash Tables", 0, 0).node().pageId();
        node(topic.id(), "Heaps", 240, 0);
        double before = career.getXpProgress().getXp();

        status(node, StatusChoice.DONE);

        assertThat(career.getXpProgress().getXp() - before).isEqualTo(NotebookRewards.PAGE_DONE_XP);
    }

    /** Set by hand, a page holds its status against its nodes until it is handed back with AUTO. */
    @Test
    void aManualStatusHoldsUntilAuto() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID structures = node(topic.id(), "Data Structures", 0, 0).node().pageId();
        UUID arrays = node(structures, "Arrays", 0, 0).node().pageId();
        node(structures, "Trees", 240, 0);

        status(structures, StatusChoice.TO_STUDY);
        status(arrays, StatusChoice.DONE);
        assertThat(statusOf(structures)).isEqualTo(NotebookStatus.TO_STUDY);

        status(structures, StatusChoice.AUTO);
        assertThat(statusOf(structures)).isEqualTo(NotebookStatus.STUDYING);
    }

    /** A finished page that gets a new, unstarted node is no longer finished. */
    @Test
    void addingANodeReopensAFinishedPage() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID structures = node(topic.id(), "Data Structures", 0, 0).node().pageId();
        UUID arrays = node(structures, "Arrays", 0, 0).node().pageId();
        status(arrays, StatusChoice.DONE);
        assertThat(statusOf(structures)).isEqualTo(NotebookStatus.DONE);

        BoardChangeResponseDTO added = node(structures, "Union-Find", 240, 0);

        assertThat(statusOf(structures)).isEqualTo(NotebookStatus.STUDYING);
        assertThat(added.changed()).extracting(c -> c.pageId()).contains(structures);
    }

    // ------------------------------------------------------------------ links

    @Test
    void aLinkedPageKeepsItsHomeAndShowsOnTheOtherBoard() {
        PageResponseDTO engineering = topic("Software Engineering");
        UUID os = node(engineering.id(), "Operating Systems", 0, 0).node().pageId();
        PageResponseDTO fundamentals = topic("Fundamentals of CS");

        BoardChangeResponseDTO linked = boardService.addNode(user, fundamentals.id(),
                new CreateNodeRequestDTO(null, null, os, null, 0.0, 0.0, null, null));

        assertThat(linked.node().linked()).isTrue();
        assertThat(linked.node().homeTopicTitle()).isEqualTo("Software Engineering");
        assertThat(pageRepository.findById(os).orElseThrow().getTopicId()).isEqualTo(engineering.id());
        assertThat(pageService.tree(user, fundamentals.id()).items())
                .filteredOn(i -> i.id().equals(os)).singleElement()
                .satisfies(i -> assertThat(i.linked()).isTrue());
    }

    /** A page put on a board below itself would count its own leaves forever. */
    @Test
    void aLinkThatWouldCloseALoopIsRefused() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID structures = node(topic.id(), "Data Structures", 0, 0).node().pageId();
        UUID trees = node(structures, "Trees", 0, 0).node().pageId();

        assertThatThrownBy(() -> boardService.addNode(user, trees,
                new CreateNodeRequestDTO(null, null, topic.id(), null, 0.0, 0.0, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_BOARD_CYCLE);
    }

    @Test
    void aPageAppearsOnceOnABoard() {
        PageResponseDTO engineering = topic("Software Engineering");
        UUID os = node(engineering.id(), "Operating Systems", 0, 0).node().pageId();
        PageResponseDTO fundamentals = topic("Fundamentals of CS");
        CreateNodeRequestDTO link = new CreateNodeRequestDTO(null, null, os, null, 0.0, 0.0, null, null);
        boardService.addNode(user, fundamentals.id(), link);

        assertThatThrownBy(() -> boardService.addNode(user, fundamentals.id(), link))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_NODE_DUPLICATE);
    }

    /** Removing a linked node with deletePage must never delete the page in its home topic. */
    @Test
    void deletingALinkedNodeOnlyUnlinks() {
        PageResponseDTO engineering = topic("Software Engineering");
        UUID os = node(engineering.id(), "Operating Systems", 0, 0).node().pageId();
        PageResponseDTO fundamentals = topic("Fundamentals of CS");
        UUID nodeId = boardService.addNode(user, fundamentals.id(),
                new CreateNodeRequestDTO(null, null, os, null, 0.0, 0.0, null, null)).node().id();

        boardService.deleteNode(user, nodeId, true);

        assertThat(pageRepository.findById(os)).isPresent();
        assertThat(nodeRepository.findById(nodeId)).isEmpty();
    }

    @Test
    void deletingAPageTakesItsSubtree() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID structures = node(topic.id(), "Data Structures", 0, 0).node().pageId();
        UUID trees = node(structures, "Trees", 0, 0).node().pageId();

        pageService.delete(user, structures);
        pageRepository.flush();

        assertThat(pageRepository.findById(structures)).isEmpty();
        assertThat(pageRepository.findById(trees)).isEmpty();
        assertThat(pageService.tree(user, topic.id()).items()).isEmpty();
    }

    @Test
    void anEdgeNeedsTwoPageNodesOfTheSameBoard() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID a = node(topic.id(), "A", 0, 0).node().id();
        UUID b = node(topic.id(), "B", 240, 0).node().id();
        PageResponseDTO other = topic("Other");
        UUID c = node(other.id(), "C", 0, 0).node().id();

        assertThat(boardService.addEdge(user, topic.id(), new CreateEdgeRequestDTO(a, b)).source()).isEqualTo(a);
        assertThatThrownBy(() -> boardService.addEdge(user, topic.id(), new CreateEdgeRequestDTO(a, c)))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_EDGE_INVALID);
    }

    // -------------------------------------------------------------- ownership

    @Test
    void aStrangerCannotReadOrBuildOnSomebodysPage() {
        PageResponseDTO topic = topic("Private notes");
        UUID page = node(topic.id(), "Journal", 0, 0).node().pageId();

        assertThatThrownBy(() -> pageService.open(stranger, page))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED);
        assertThatThrownBy(() -> boardService.addNode(stranger, topic.id(),
                new CreateNodeRequestDTO(null, "Mine now", null, null, 0.0, 0.0, null, null)))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED);
    }

    /** Linking somebody else's page onto your own board would expose it through yours. */
    @Test
    void aStrangerCannotLinkSomebodysPageOntoTheirBoard() {
        UUID mine = node(topic("Mine").id(), "Secret", 0, 0).node().pageId();
        PageResponseDTO theirs = pageService.createTopic(stranger,
                new CreateTopicRequestDTO("Theirs", null, null, null, null, null));

        assertThatThrownBy(() -> boardService.addNode(stranger, theirs.id(),
                new CreateNodeRequestDTO(null, null, mine, null, 0.0, 0.0, null, null)))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED);
    }

    @Test
    void aFocusCycleCannotBeFiledOnSomebodysPage() {
        UUID page = node(topic("Mine").id(), "Trees", 0, 0).node().pageId();

        assertThatThrownBy(() -> focusService.recordCycle(stranger, cycle(page, 25)))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED);
    }

    // ---------------------------------------------------------------- content

    /** The text the AI reads is derived on the server from the document, never sent alongside it. */
    @Test
    void savingContentDerivesThePlainText() {
        UUID page = node(topic("Software Engineering").id(), "Trees", 0, 0).node().pageId();
        String doc = """
                [{"type":"heading","props":{"level":2},"content":[{"type":"text","text":"BST deletion","styles":{}}]},
                 {"type":"paragraph","content":[{"type":"text","text":"Use the ","styles":{}},
                   {"type":"text","text":"in-order successor","styles":{"bold":true}}]},
                 {"type":"roadmapBoard","props":{},"content":[]}]
                """;

        pageService.saveContent(user, page, new UpdateContentRequestDTO(doc));

        String text = pageRepository.findById(page).orElseThrow().getContentText();
        assertThat(text).isEqualTo("BST deletion\nUse the in-order successor");
    }

    @Test
    void focusMinutesCountThePageAndEverythingUnderIt() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID structures = node(topic.id(), "Data Structures", 0, 0).node().pageId();
        UUID trees = node(structures, "Trees", 0, 0).node().pageId();

        focusService.recordCycle(user, cycle(structures, 25));
        focusService.recordCycle(user, cycle(trees, 50));

        assertThat(pageService.open(user, structures).focusMinutes()).isEqualTo(75);
        assertThat(pageService.open(user, trees).focusMinutes()).isEqualTo(50);
    }

    // ------------------------------------------------------------------ cards

    @Test
    void aGoodAnswerSendsTheCardToTomorrowAndOutOfTodaysQueue() {
        UUID page = node(topic("Software Engineering").id(), "Trees", 0, 0).node().pageId();
        CardDTO card = cardService.create(user, page, new CreateCardRequestDTO("Front", "Back", null));
        assertThat(cardService.due(user, null).total()).isEqualTo(1);

        var review = cardService.review(user, card.id(), CardRating.GOOD);

        assertThat(review.intervalDays()).isEqualTo(1);
        assertThat(review.dueAgainToday()).isFalse();
        assertThat(cardService.due(user, null).total()).isZero();
    }

    @Test
    void againKeepsTheCardInTodaysQueue() {
        UUID page = node(topic("Software Engineering").id(), "Trees", 0, 0).node().pageId();
        CardDTO card = cardService.create(user, page, new CreateCardRequestDTO("Front", "Back", null));

        var review = cardService.review(user, card.id(), CardRating.AGAIN);

        assertThat(review.dueAgainToday()).isTrue();
        assertThat(cardService.due(user, null).total()).isEqualTo(1);
    }

    /**
     * A session pays one XP per review, at most thirty a day, and a review is paid once. Forty
     * reviews pay thirty; finishing again pays nothing.
     */
    @Test
    void finishingASessionPaysPerReviewUpToTheDailyCap() {
        UUID page = node(topic("Software Engineering").id(), "Trees", 0, 0).node().pageId();
        CardDTO card = cardService.create(user, page, new CreateCardRequestDTO("Front", "Back", null));
        for (int i = 0; i < 40; i++) {
            cardService.review(user, card.id(), CardRating.AGAIN);
        }

        FinishReviewResponseDTO first = cardService.finish(user);
        FinishReviewResponseDTO second = cardService.finish(user);

        assertThat(first.paidReviews()).isEqualTo(NotebookRewards.DAILY_REVIEW_XP_CAP);
        assertThat(first.xpEarned()).isEqualTo(NotebookRewards.DAILY_REVIEW_XP_CAP * NotebookRewards.CARD_REVIEW_XP);
        assertThat(first.streak()).isEqualTo(1);
        assertThat(second.paidReviews()).isZero();
        assertThat(second.refreshUi()).isNull();
    }

    @Test
    void theQueueCanBeScopedToAPageAndWhatIsUnderIt() {
        PageResponseDTO topic = topic("Software Engineering");
        UUID structures = node(topic.id(), "Data Structures", 0, 0).node().pageId();
        UUID trees = node(structures, "Trees", 0, 0).node().pageId();
        UUID os = node(topic.id(), "Operating Systems", 240, 0).node().pageId();
        cardService.create(user, trees, new CreateCardRequestDTO("Tree card", "x", null));
        cardService.create(user, os, new CreateCardRequestDTO("OS card", "x", null));

        assertThat(cardService.due(user, structures).cards())
                .extracting(c -> c.front()).containsExactly("Tree card");
        assertThat(cardService.due(user, topic.id()).total()).isEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private PageResponseDTO topic(String title) {
        return pageService.createTopic(user, new CreateTopicRequestDTO(title, null, null, null, null, null));
    }

    private BoardChangeResponseDTO node(UUID boardPageId, String title, double x, double y) {
        return boardService.addNode(user, boardPageId,
                new CreateNodeRequestDTO(null, title, null, null, x, y, null, null));
    }

    private StatusChangeResponseDTO status(UUID pageId, StatusChoice choice) {
        return pageService.setStatus(user, pageId, new SetStatusRequestDTO(choice));
    }

    private NotebookStatus statusOf(UUID pageId) {
        return pageRepository.findById(pageId).orElseThrow().getStatus();
    }

    private double userXp() {
        return userRepository.findById(user.getId()).orElseThrow().getXpProgress().getXp();
    }

    private RecordCycleRequestDTO cycle(UUID pageId, int minutes) {
        Instant end = Instant.now();
        return new RecordCycleRequestDTO(null, CycleKind.POMODORO, end.minusSeconds(minutes * 60L), end, minutes, pageId);
    }

    private User newUser(String tag) {
        User u = new User();
        u.setName(tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
