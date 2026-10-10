package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.aiAgent.notebook.StudyBoardEditor;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookAiService;
import beyou.beyouapp.backend.domain.notebook.ai.NotebookLlm;
import beyou.beyouapp.backend.domain.notebook.ai.dto.AiCardsRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.ExplainRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.LlmPayloads;
import beyou.beyouapp.backend.domain.notebook.ai.dto.RoadmapDraftRequestDTO;
import beyou.beyouapp.backend.domain.notebook.ai.dto.StudyLevel;
import beyou.beyouapp.backend.domain.notebook.ai.dto.SuggestNodesRequestDTO;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.domain.notebook.study.StudyOutputKind;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * No notebook model call runs inside a database transaction.
 *
 * <p>A call can take up to NotebookLlm's ninety-second budget, and a transaction open around it
 * holds one of the pool's ten connections for all of that time. Ten slow answers at once and
 * every other request in the app waits for a connection. So each path reads in one short
 * transaction, calls the model with none open, and writes in a second short one.
 *
 * <p>Deliberately NOT {@code @Transactional}, unlike NotebookAiIT: a test transaction would be
 * joined by every call and make the assertion meaningless. The rows this creates are committed,
 * so the user is deleted afterwards and the database cascades the rest.
 *
 * <p>The mocked model records whether a transaction is active at the moment it is asked. The
 * other assertions check that the work around the call still lands (cards saved, the chat turn
 * stored, the output kept), since the easy way to pass the first one is to stop writing.
 */
class NotebookModelCallTransactionIT extends AbstractIntegrationTest {

    @MockitoBean private NotebookLlm llm;

    @Autowired private NotebookAiService aiService;
    @Autowired private NotebookStudyService studyService;
    @Autowired private StudyBoardEditor boardEditor;
    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<Boolean> transactionOpenDuringCall = new ArrayList<>();
    private User user;
    private UUID topic;
    private UUID trees;

    @BeforeEach
    void seed() {
        user = newUser();
        topic = pageService.createTopic(user, new CreateTopicRequestDTO("Data Structures", null, null, null, null, null)).id();
        trees = boardService.addNode(user, topic,
                new CreateNodeRequestDTO(null, "Trees", null, null, 0.0, 0.0, null, null, null)).node().pageId();
        pageService.saveContent(user, trees, new UpdateContentRequestDTO(
                "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":"
                        + "\"A heap keeps the smallest key at the root.\",\"styles\":{}}]}]", null));

        answer(LlmPayloads.RoadmapPayload.class, () -> new LlmPayloads.RoadmapPayload(List.of(
                new LlmPayloads.RoadmapNode("Heaps", "Priority queues.", List.of("Binary heap"), 6, false))));
        answer(LlmPayloads.SuggestionsPayload.class, () -> new LlmPayloads.SuggestionsPayload(List.of(
                new LlmPayloads.Suggestion("Graphs", "Next after trees."))));
        answer(LlmPayloads.AnswerPayload.class, () -> new LlmPayloads.AnswerPayload("At the root [1].", List.of(1)));
        answer(LlmPayloads.CardsPayload.class, () -> new LlmPayloads.CardsPayload(List.of(
                new LlmPayloads.Card("Where is the smallest key in a min-heap?", "At the root.", 1))));
        answer(LlmPayloads.OverviewPayload.class, () -> new LlmPayloads.OverviewPayload(
                "About heaps [1].", List.of("Why the root?")));
        answer(LlmPayloads.QuizPayload.class, () -> new LlmPayloads.QuizPayload(List.of(
                question(0), question(1), question(2), question(3))));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    @Test
    void draftingARoadmapCallsTheModelWithNoTransactionOpen() {
        var draft = aiService.roadmapDraft(user, new RoadmapDraftRequestDTO(
                "Data Structures", null, StudyLevel.NEW, null, null, null, null, null));

        assertThat(draft.nodes()).extracting(n -> n.title()).containsExactly("Heaps");
        assertNoTransactionDuringTheCall();
    }

    @Test
    void suggestingNodesCallsTheModelWithNoTransactionOpen() {
        assertThat(aiService.suggestNodes(user, topic, new SuggestNodesRequestDTO(false)))
                .extracting(s -> s.title()).containsExactly("Graphs");
        assertNoTransactionDuringTheCall();
    }

    @Test
    void explainingCallsTheModelWithNoTransactionOpen() {
        assertThat(aiService.explain(user, trees, new ExplainRequestDTO("smallest key at the root")).markdown())
                .isEqualTo("At the root [1].");
        assertNoTransactionDuringTheCall();
    }

    @Test
    void draftingCardsCallsTheModelWithNoTransactionOpenAndStillSavesThem() {
        assertThat(aiService.cards(user, trees, new AiCardsRequestDTO(null, 2))).hasSize(1);

        assertNoTransactionDuringTheCall();
        assertThat(count("notebook_cards", "page_id", trees)).isEqualTo(1);
    }

    @Test
    void theAssistantsCardToolCallsTheModelWithNoTransactionOpen() {
        boardEditor.generateCards(user, topic.toString(), "Trees", 2, null);

        assertNoTransactionDuringTheCall();
        assertThat(count("notebook_cards", "page_id", trees)).isEqualTo(1);
    }

    @Test
    void aStudyChatCallsTheModelWithNoTransactionOpenAndStillStoresTheTurn() {
        studyService.chat(user, trees, "Where is the smallest key?");

        assertNoTransactionDuringTheCall();
        assertThat(count("notebook_chat_messages", "page_id", trees)).isEqualTo(2);
    }

    @Test
    void studioOutputsCallTheModelWithNoTransactionOpenAndStillKeepTheResult() {
        studyService.generate(user, trees, StudyOutputKind.SUMMARY);
        studyService.generate(user, trees, StudyOutputKind.QUIZ);
        studyService.generate(user, trees, StudyOutputKind.OVERVIEW);
        studyService.generate(user, trees, StudyOutputKind.OVERVIEW);

        assertNoTransactionDuringTheCall();
        // Summary, quiz, and one overview: the second overview replaced the first.
        assertThat(count("notebook_study_outputs", "page_id", trees)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- helpers

    private <T> void answer(Class<T> type, Supplier<T> payload) {
        when(llm.call(eq(type), anyString(), any())).thenAnswer(invocation -> {
            transactionOpenDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
            return payload.get();
        });
    }

    private void assertNoTransactionDuringTheCall() {
        assertThat(transactionOpenDuringCall).as("model calls made").isNotEmpty();
        assertThat(transactionOpenDuringCall)
                .as("a transaction was open while the model was asked, holding a pooled connection")
                .containsOnly(false);
    }

    private long count(String table, String column, UUID id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
        return n == null ? 0 : n;
    }

    private static LlmPayloads.QuizQuestion question(int answer) {
        return new LlmPayloads.QuizQuestion("Question " + answer + "?", List.of("a", "b", "c", "d"), answer, "Because.", 1);
    }

    private User newUser() {
        User u = new User();
        u.setName("model-call-tx");
        u.setEmail("model-call-tx-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
