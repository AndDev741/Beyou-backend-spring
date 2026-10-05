package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.MarkdownBlocks;
import beyou.beyouapp.backend.domain.notebook.NotebookPageRepository;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.NotebookRewards;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookLlm;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AiCardsRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.CreateFromDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.DraftNodeInputDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.LlmPayloads;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.StudyLevel;
import beyou.beyouapp.backend.domain.notebook.ai.dto.SuggestNodesRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.BoardResponseDTO;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.dto.CardDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.PageResponseDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.domain.notebook.study.StudyOutputKind;
import beyou.beyouapp.backend.domain.notebook.study.dto.ChatTurnDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.QuizResultDTO;
import beyou.beyouapp.backend.domain.notebook.study.dto.StudyOutputDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * The notebook's AI paths with the model replaced by a mock. What is under test is everything
 * around the model: what it is given, what is kept of what it says, and what gets created. A
 * mocked model is also the only way to hand these paths the malformed output free tiers produce.
 */
@Transactional
class NotebookAiIT extends AbstractIntegrationTest {

    @MockitoBean private NotebookLlm llm;

    @Autowired private NotebookAiService aiService;
    @Autowired private NotebookStudyService studyService;
    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private NotebookPageRepository pageRepository;
    @Autowired private UserRepository userRepository;

    private User user;

    @BeforeEach
    void seed() {
        user = newUser("notebook-ai");
    }

    // ------------------------------------------------------------------ draft

    /**
     * The draft is cleaned: duplicates dropped, optional nodes kept out of the hours total, and a
     * node the person already has elsewhere offered as a link instead of a copy.
     */
    @Test
    void theDraftIsCleanedAndOffersLinksToWhatAlreadyExists() {
        UUID engineering = topic("Software Engineering");
        UUID os = node(engineering, "Operating System");
        when(llm.call(eq(LlmPayloads.RoadmapPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.RoadmapPayload(List.of(
                        new LlmPayloads.RoadmapNode("Discrete Math", "Proofs.", List.of("Logic", "Logic", "Sets"), 18, false),
                        new LlmPayloads.RoadmapNode("discrete math", "Duplicate.", List.of(), 5, false),
                        new LlmPayloads.RoadmapNode("Operating Systems", "Processes.", List.of("Threads"), 20, false),
                        new LlmPayloads.RoadmapNode("Compilers", "Optional.", List.of(), 18, true),
                        new LlmPayloads.RoadmapNode("  ", "No title.", List.of(), 3, false))));

        RoadmapDraftDTO draft = aiService.roadmapDraft(user, new RoadmapDraftRequestDTO(
                "Fundamentals of CS", "Interviews", StudyLevel.SOME, 6, null, null, null, null));

        assertThat(draft.nodes()).extracting(n -> n.title())
                .containsExactly("Discrete Math", "Operating Systems", "Compilers");
        assertThat(draft.nodes().get(0).subtopics()).containsExactly("Logic", "Sets");
        assertThat(draft.nodes().get(1).existingPageId()).isEqualTo(os);
        assertThat(draft.nodes().get(1).existingTopicTitle()).isEqualTo("Software Engineering");
        assertThat(draft.totalHours()).isEqualTo(38);
    }

    @Test
    void creatingTheDraftBuildsTheTopicItsBoardAndABoardPerNode() {
        UUID engineering = topic("Software Engineering");
        UUID os = node(engineering, "Operating Systems");

        PageResponseDTO created = aiService.createFromDraft(user, new CreateFromDraftRequestDTO(
                "Fundamentals of CS", "The theory behind what I use.", null, null, null, null, List.of(
                        new DraftNodeInputDTO("Discrete Math", "Proofs and counting.", List.of("Logic", "Sets"), 18, null),
                        new DraftNodeInputDTO("Operating Systems", null, null, null, os))));

        BoardResponseDTO board = boardService.board(user, created.id());
        assertThat(board.nodes()).extracting(n -> n.title()).containsExactly("Discrete Math", "Operating Systems");
        assertThat(board.nodes().get(1).linked()).isTrue();
        assertThat(board.edges()).hasSize(1);
        UUID math = board.nodes().get(0).pageId();
        assertThat(boardService.board(user, math).nodes()).extracting(n -> n.title()).containsExactly("Logic", "Sets");
        assertThat(pageRepository.findById(math).orElseThrow().getContent()).contains(MarkdownBlocks.BOARD_BLOCK_TYPE);
        assertThat(pageRepository.findById(math).orElseThrow().getContentText()).isEqualTo("Proofs and counting.");
        assertThat(created.progress().total()).isEqualTo(3); // Logic, Sets, and the linked OS leaf
    }

    /** The grid the web board's "Tidy up" uses too: three to a row, each row after the last. */
    @Test
    void aDraftIsLaidOutThreeToARowInTheOrderItWasDrafted() {
        PageResponseDTO created = aiService.createFromDraft(user, new CreateFromDraftRequestDTO(
                "Software Engineering", null, null, null, null, null, List.of(
                        new DraftNodeInputDTO("Fundamentals", null, null, 10, null),
                        new DraftNodeInputDTO("Data Structures", null, null, 10, null),
                        new DraftNodeInputDTO("Operating Systems", null, null, 10, null),
                        new DraftNodeInputDTO("Networks", null, null, 10, null))));

        BoardResponseDTO board = boardService.board(user, created.id());

        assertThat(board.nodes()).extracting(n -> n.title() + "@" + (int) n.x() + "," + (int) n.y())
                .containsExactly("Fundamentals@40,0", "Data Structures@280,0", "Operating Systems@520,0", "Networks@40,140");
    }

    @Test
    void suggestionsNeverRepeatANodeAlreadyOnTheBoard() {
        UUID topic = topic("Software Engineering");
        node(topic, "Algorithms");
        when(llm.call(eq(LlmPayloads.SuggestionsPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.SuggestionsPayload(List.of(
                        new LlmPayloads.Suggestion("algorithms", "Already there."),
                        new LlmPayloads.Suggestion("Cloud and DevOps", "Deploying what you build."))));

        assertThat(aiService.suggestNodes(user, topic, new SuggestNodesRequestDTO(false)))
                .extracting(s -> s.title()).containsExactly("Cloud and DevOps");
    }

    // ------------------------------------------------------------------- chat

    /** A citation the reader cannot open is worse than none: [7] was never given, so it goes. */
    @Test
    void aChatAnswerKeepsOnlyCitationsThatPointAtSomething() {
        UUID trees = pageWithNotes("Trees", "To delete a node with two children use the in-order successor.");
        when(llm.call(eq(LlmPayloads.AnswerPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.AnswerPayload("Use the successor [1]. Also this [7].", List.of(1, 7)));

        ChatTurnDTO turn = studyService.chat(user, trees, "How do I delete a node with two children?");

        assertThat(turn.answer().content()).isEqualTo("Use the successor [1]. Also this.");
        assertThat(turn.answer().citations()).singleElement()
                .satisfies(c -> assertThat(c.pageId()).isEqualTo(trees));
        assertThat(studyService.study(user, trees).messages()).extracting(m -> m.role())
                .containsExactly("USER", "ASSISTANT");
    }

    /** Grounded means grounded: with nothing to stand on, the model is not even asked. */
    @Test
    void anEmptyPageWithNoSourcesIsNotSentToTheModel() {
        UUID empty = node(topic("Empty"), "Nothing yet");

        assertThatThrownBy(() -> studyService.chat(user, empty, "What is this?"))
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.NOTEBOOK_NOTHING_TO_STUDY);
        verify(llm, never()).call(any(), anyString(), any());
    }

    // ------------------------------------------------------------------ quiz

    /**
     * Questions that cannot be graded honestly are dropped, answers stay on the server until
     * grading, and passing pays once.
     */
    @Test
    void aQuizHidesItsAnswersAndPaysForTheFirstPassOnly() {
        UUID trees = pageWithNotes("Trees", "A BST keeps left keys smaller and right keys larger.");
        when(llm.call(eq(LlmPayloads.QuizPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.QuizPayload(List.of(
                        question(0), question(1), question(2), question(3),
                        new LlmPayloads.QuizQuestion("Only three options?", List.of("a", "b", "c"), 0, "x", 1))));

        StudyOutputDTO quiz = studyService.generate(user, trees, StudyOutputKind.QUIZ);
        assertThat(quiz.questions()).hasSize(4);
        assertThat(quiz.markdown()).isNull();

        QuizResultDTO first = studyService.grade(user, quiz.id(), List.of(0, 1, 2, 3));
        QuizResultDTO again = studyService.grade(user, quiz.id(), List.of(0, 1, 2, 3));

        assertThat(first.passed()).isTrue();
        assertThat(first.xpEarned()).isEqualTo(NotebookRewards.QUIZ_PASSED_XP);
        assertThat(first.answers()).allSatisfy(a -> assertThat(a.right()).isTrue());
        assertThat(again.xpEarned()).isZero();
    }

    @Test
    void failingAQuizPaysNothing() {
        UUID trees = pageWithNotes("Trees", "A BST keeps left keys smaller and right keys larger.");
        when(llm.call(eq(LlmPayloads.QuizPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.QuizPayload(List.of(question(0), question(1), question(2), question(3))));
        StudyOutputDTO quiz = studyService.generate(user, trees, StudyOutputKind.QUIZ);

        QuizResultDTO result = studyService.grade(user, quiz.id(), List.of(0, 0, 0, -1));

        assertThat(result.score()).isEqualTo(1);
        assertThat(result.passed()).isFalse();
        assertThat(result.xpEarned()).isZero();
        assertThat(result.refreshUi()).isNull();
    }

    @Test
    void aNewOverviewReplacesTheLastOne() {
        UUID trees = pageWithNotes("Trees", "Rotations keep AVL trees balanced.");
        when(llm.call(eq(LlmPayloads.OverviewPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.OverviewPayload("About rotations [1].", List.of("When does it rotate twice?")));

        studyService.generate(user, trees, StudyOutputKind.OVERVIEW);
        studyService.generate(user, trees, StudyOutputKind.OVERVIEW);

        var study = studyService.study(user, trees);
        assertThat(study.overview().summary()).isEqualTo("About rotations [1].");
        assertThat(study.outputs()).isEmpty();
    }

    @Test
    void aiCardsAreSavedWithWhereTheyCameFrom() {
        UUID trees = pageWithNotes("Trees", "A heap keeps the smallest key at the root.");
        when(llm.call(eq(LlmPayloads.CardsPayload.class), anyString(), any())).thenReturn(
                new LlmPayloads.CardsPayload(List.of(
                        new LlmPayloads.Card("Where is the smallest key in a min-heap?", "At the root.", 1),
                        new LlmPayloads.Card("", "Blank front, dropped.", 1))));

        List<CardDTO> cards = aiService.cards(user, trees, new AiCardsRequestDTO(null, 4));

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.front()).isEqualTo("Where is the smallest key in a min-heap?");
            assertThat(card.sourceLabel()).isEqualTo("Your page \"Trees\"");
        });
    }

    // ---------------------------------------------------------------- helpers

    private static LlmPayloads.QuizQuestion question(int answer) {
        return new LlmPayloads.QuizQuestion("Question " + answer + "?", List.of("a", "b", "c", "d"), answer, "Because.", 1);
    }

    private UUID topic(String title) {
        return pageService.createTopic(user, new CreateTopicRequestDTO(title, null, null, null, null, null)).id();
    }

    private UUID node(UUID boardPageId, String title) {
        return boardService.addNode(user, boardPageId,
                new CreateNodeRequestDTO(null, title, null, null, 0.0, 0.0, null, null)).node().pageId();
    }

    private UUID pageWithNotes(String title, String notes) {
        UUID page = node(topic("Data Structures"), title);
        pageService.saveContent(user, page, new UpdateContentRequestDTO(
                "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + notes + "\",\"styles\":{}}]}]"));
        return page;
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
