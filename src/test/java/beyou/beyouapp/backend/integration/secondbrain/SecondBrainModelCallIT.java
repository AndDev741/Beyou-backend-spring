package beyou.beyouapp.backend.integration.secondbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.secondbrain.LearnedFact;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainAi;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestion;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionStatus;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainService;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainVerdict;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainInsightDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Second Brain with a model configured: what the three model-backed routes do with its answer,
 * what they do when it fails, and that no transaction is open while they wait for it.
 *
 * <p>That last part is the rule this class exists for. A model call can take most of a minute,
 * and inside a transaction it would hold one of the pool's ten connections for all of it. So
 * each route reads in a short transaction, calls the model with none open, and writes in
 * another. {@code NotebookModelCallTransactionIT} keeps the same promise for the notebook.
 *
 * <p>Deliberately NOT {@code @Transactional}, unlike {@link SecondBrainServiceIT}: a test
 * transaction would be joined by every call and make the assertion meaningless. The rows are
 * committed, so the user is deleted afterwards and the database cascades the rest.
 *
 * <p>The model is a mock of {@link SecondBrainAi} that records whether a transaction is active
 * when it is asked. What it does with a real answer is {@code SecondBrainAiTest}'s subject.
 */
@TestPropertySource(properties = {
        "ai.llm.base-url=http://127.0.0.1:9/v1",
        "ai.llm.api-key=test-key",
        "ai.llm.model=test-model"
})
class SecondBrainModelCallIT extends AbstractIntegrationTest {

    @MockitoBean private SecondBrainAi ai;

    @Autowired private SecondBrainService service;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<Boolean> transactionOpenDuringCall = new ArrayList<>();
    private User user;

    @BeforeEach
    void seed() {
        user = newUser();
        // Every test below is about an account that already uses the AI side of the page.
        // The one about an account that does not builds its own.
        startAi(user);
    }

    /** What asking for a first overview leaves behind, and what switches the model on. */
    private void startAi(User account) {
        jdbc.update("INSERT INTO second_brain_insights (user_id, text, verdict, generated_at) "
                + "VALUES (?, 'A first read.', 'TOO_EARLY', now())", account.getId());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());
    }

    // ---- before the first overview ----

    /**
     * The first overview is asked for from a screen that says the text goes to an external AI
     * provider. Until a person has done that, opening the page and answering its questions
     * must not send anything of theirs to the model.
     */
    @Test
    @DisplayName("until a person asks for their first overview, nothing on the page reaches the model")
    void nothingReachesTheModelBeforeTheFirstOverview() {
        User fresh = newUser();
        try {
            List<SecondBrainQuestionDTO> open = service.refill(fresh);
            assertThat(open).extracting(SecondBrainQuestionDTO::kind).containsExactly(
                    SecondBrainQuestionKind.PROFILE_OBJECTIVES,
                    SecondBrainQuestionKind.PROFILE_LIKES,
                    SecondBrainQuestionKind.PROFILE_ABOUT);

            SecondBrainAnswerResultDTO result = service.answer(fresh, open.get(0).id(),
                    new SecondBrainAnswerRequestDTO("Run a half marathon by June"));
            assertThat(result.learned()).extracting(SecondBrainFactDTO::text)
                    .as("the answer is kept as the person wrote it").containsExactly("Run a half marathon by June");

            org.mockito.Mockito.verifyNoInteractions(ai);

            // Asking for the overview is the switch.
            when(ai.insight(eq(fresh), any(), anyString()))
                    .thenReturn(new SecondBrainAi.Overview(SecondBrainVerdict.TOO_EARLY, "Too early to tell."));
            service.refreshInsight(fresh);
            when(ai.followUps(eq(fresh), any(), anyString(), anyList(), anyInt()))
                    .thenReturn(List.of("What is the half marathon for?"));

            assertThat(service.refill(fresh)).extracting(SecondBrainQuestionDTO::kind)
                    .contains(SecondBrainQuestionKind.FOLLOW_UP);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", fresh.getId());
        }
    }

    // ---- refill ----

    @Test
    @DisplayName("a refill asks for as many follow-ups as there is room, with no transaction open, once per six hours")
    void refillAsksTheModelOutsideATransaction() {
        when(ai.followUps(eq(user), any(), anyString(), anyList(), anyInt())).thenAnswer(call -> {
            recordTransaction();
            return List.of("What is the half marathon for?", "What got in the way of reading?", "One too many?");
        });

        List<SecondBrainQuestionDTO> open = service.refill(user);

        assertNoTransactionDuringTheCall();
        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        verify(ai).followUps(eq(user), any(), data.capture(), anyList(), eq(2));
        assertThat(data.getValue()).contains("ROUTINES\n", "GOALS\n", "MOOD (", "PROFILE (")
                .doesNotContain("CALENDAR").doesNotContain("NOTEBOOK").doesNotContain("IDEAS");
        assertThat(open).hasSize(SecondBrainQuestion.MAX_OPEN);
        assertThat(open).extracting(SecondBrainQuestionDTO::kind).containsExactly(
                SecondBrainQuestionKind.PROFILE_OBJECTIVES,
                SecondBrainQuestionKind.PROFILE_LIKES,
                SecondBrainQuestionKind.PROFILE_ABOUT,
                SecondBrainQuestionKind.FOLLOW_UP,
                SecondBrainQuestionKind.FOLLOW_UP);
        assertThat(open).extracting(SecondBrainQuestionDTO::text)
                .endsWith("What is the half marathon for?", "What got in the way of reading?");

        // Room again, but the model was asked minutes ago: the pause holds.
        service.skip(user, open.get(3).id());
        assertThat(service.refill(user)).hasSize(4);
        verify(ai, times(1)).followUps(any(), any(), anyString(), anyList(), anyInt());
    }

    @Test
    @DisplayName("a model that fails costs the refill nothing: the seeds still come back")
    void aFailingModelDoesNotFailTheRefill() {
        when(ai.followUps(any(), any(), anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("upstream 502"));

        List<SecondBrainQuestionDTO> open = service.refill(user);

        assertThat(open).extracting(SecondBrainQuestionDTO::kind).containsExactly(
                SecondBrainQuestionKind.PROFILE_OBJECTIVES,
                SecondBrainQuestionKind.PROFILE_LIKES,
                SecondBrainQuestionKind.PROFILE_ABOUT);
    }

    // ---- answer ----

    @Test
    @DisplayName("a profile answer is closed first, then split by the model with no transaction open")
    void profileAnswerIsSplitOutsideATransaction() {
        UUID objectives = service.refill(user).get(0).id();
        List<String> statusSeenByTheModel = new ArrayList<>();
        when(ai.profileFacts(eq(user), any(), eq(SecondBrainFactKind.OBJECTIVE), anyString(), anyString(), anyList()))
                .thenAnswer(call -> {
                    recordTransaction();
                    // Committed before the model is asked, so a second submit already gets a 409.
                    statusSeenByTheModel.add(jdbc.queryForObject(
                            "SELECT status FROM second_brain_questions WHERE id = ?", String.class, objectives));
                    return List.of(
                            new LearnedFact(SecondBrainFactKind.OBJECTIVE, "Run a half marathon by June"),
                            new LearnedFact(SecondBrainFactKind.OBJECTIVE, "Read twelve books"));
                });

        SecondBrainAnswerResultDTO result = service.answer(user, objectives,
                new SecondBrainAnswerRequestDTO("Run a half marathon by June and read twelve books"));

        assertNoTransactionDuringTheCall();
        assertThat(statusSeenByTheModel).containsExactly("ANSWERED");
        assertThat(result.question().status()).isEqualTo(SecondBrainQuestionStatus.ANSWERED);
        assertThat(result.learned()).extracting(SecondBrainFactDTO::text)
                .containsExactly("Run a half marathon by June", "Read twelve books");
        assertThat(result.learned()).extracting(SecondBrainFactDTO::origin).containsOnly(SecondBrainFactOrigin.ANSWER);
        assertThat(service.overview(user).facts()).isEqualTo(result.learned());
    }

    @Test
    @DisplayName("when the model fails or finds nothing, a profile answer is kept as one fact")
    void profileAnswerFallsBackToItself() {
        List<SecondBrainQuestionDTO> seeds = service.refill(user);
        when(ai.profileFacts(any(), any(), eq(SecondBrainFactKind.OBJECTIVE), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalStateException("timeout"));
        when(ai.profileFacts(any(), any(), eq(SecondBrainFactKind.LIKE), anyString(), anyString(), anyList()))
                .thenReturn(List.of());

        SecondBrainAnswerResultDTO failed = service.answer(user, seeds.get(0).id(), new SecondBrainAnswerRequestDTO("Sleep more"));
        SecondBrainAnswerResultDTO empty = service.answer(user, seeds.get(1).id(), new SecondBrainAnswerRequestDTO("Cooking"));

        assertThat(failed.learned()).extracting(SecondBrainFactDTO::kind, SecondBrainFactDTO::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SecondBrainFactKind.OBJECTIVE, "Sleep more"));
        assertThat(empty.learned()).extracting(SecondBrainFactDTO::kind, SecondBrainFactDTO::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SecondBrainFactKind.LIKE, "Cooking"));
    }

    @Test
    @DisplayName("a check-in answer keeps only what the model found lasting, and nothing when it fails")
    void checkInAnswerLearnsOnlyWhatLasts() {
        UUID first = question(SecondBrainQuestionKind.CHECK_IN, "How is your day going?");
        UUID second = question(SecondBrainQuestionKind.FOLLOW_UP, "What got in the way of reading?");
        when(ai.lastingFacts(eq(user), any(), eq("How is your day going?"), anyString(), anyList()))
                .thenAnswer(call -> {
                    recordTransaction();
                    return List.of(new LearnedFact(SecondBrainFactKind.ABOUT, "Started a new job in October"));
                });
        when(ai.lastingFacts(eq(user), any(), eq("What got in the way of reading?"), anyString(), anyList()))
                .thenThrow(new IllegalStateException("upstream 502"));

        SecondBrainAnswerResultDTO learned = service.answer(user, first, new SecondBrainAnswerRequestDTO("First day at the new job"));
        SecondBrainAnswerResultDTO nothing = service.answer(user, second, new SecondBrainAnswerRequestDTO("Too tired"));

        assertNoTransactionDuringTheCall();
        assertThat(learned.learned()).extracting(SecondBrainFactDTO::kind, SecondBrainFactDTO::text).containsExactly(
                org.assertj.core.groups.Tuple.tuple(SecondBrainFactKind.ABOUT, "Started a new job in October"));
        assertThat(nothing.learned()).isEmpty();
        assertThat(nothing.question().status()).isEqualTo(SecondBrainQuestionStatus.ANSWERED);
        assertThat(nothing.question().answer()).isEqualTo("Too tired");
    }

    // ---- insight ----

    @Test
    @DisplayName("refreshing the overview asks the model with no transaction open and replaces the one row")
    void refreshWritesTheOverviewOutsideATransaction() {
        ArgumentCaptor<String> data = ArgumentCaptor.forClass(String.class);
        when(ai.insight(eq(user), any(), data.capture())).thenAnswer(call -> {
            recordTransaction();
            return new SecondBrainAi.Overview(SecondBrainVerdict.ON_COURSE, "Your mornings hold.");
        });

        SecondBrainInsightDTO first = service.refreshInsight(user);

        assertNoTransactionDuringTheCall();
        // The overview is built from what the person did AND what they answered, so the
        // answers section is always part of what the model is shown, even while it is empty.
        assertThat(data.getValue()).contains("RECENT ANSWERS (what the person told Beyou, newest first)");
        assertThat(first.text()).isEqualTo("Your mornings hold.");
        assertThat(first.verdict()).isEqualTo(SecondBrainVerdict.ON_COURSE);
        assertThat(first.stale()).isFalse();
        assertThat(first.generatedAt()).isNotNull();
        assertThat(service.overview(user).insight()).isEqualTo(first);
        assertThat(service.overview(user).aiAvailable()).isTrue();

        when(ai.insight(eq(user), any(), anyString()))
                .thenReturn(new SecondBrainAi.Overview(SecondBrainVerdict.DRIFTING, "Your evenings slipped."));
        SecondBrainInsightDTO second = service.refreshInsight(user);
        assertThat(second.text()).isEqualTo("Your evenings slipped.");
        assertThat(second.verdict()).as("the verdict is replaced with the text").isEqualTo(SecondBrainVerdict.DRIFTING);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM second_brain_insights WHERE user_id = ?",
                Integer.class, user.getId())).isEqualTo(1);

        // A failed refresh leaves the last overview where it was.
        when(ai.insight(eq(user), any(), anyString()))
                .thenThrow(new BusinessException(ErrorKey.AI_UNAVAILABLE, "Second Brain AI is unavailable"));
        assertThatThrownBy(() -> service.refreshInsight(user))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.AI_UNAVAILABLE);
        assertThat(service.overview(user).insight().text()).isEqualTo("Your evenings slipped.");
    }

    // ---- helpers ----

    private void recordTransaction() {
        transactionOpenDuringCall.add(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private void assertNoTransactionDuringTheCall() {
        assertThat(transactionOpenDuringCall).as("the model was asked").isNotEmpty();
        assertThat(transactionOpenDuringCall).as("no transaction while the model is asked").containsOnly(false);
    }

    private UUID question(SecondBrainQuestionKind kind, String text) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO second_brain_questions (id, user_id, kind, text, status, created_at) "
                + "VALUES (?, ?, ?, ?, 'OPEN', now())", id, user.getId(), kind.name(), text);
        return id;
    }

    private User newUser() {
        User account = new User();
        account.setName("brain-model");
        account.setEmail("brain-model-" + UUID.randomUUID() + "@test.com");
        account.setPassword("password123");
        account.setGoogleAccount(false);
        account.setTimezone("UTC");
        account.setLanguageInUse("en");
        account.setCompletedDays(new HashSet<>());
        account.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        account.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(account);
    }
}
