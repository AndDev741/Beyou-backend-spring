package beyou.beyouapp.backend.domain.secondbrain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.aiAgent.llm.LlmProperties;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainStore.RefillPlan;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainInsightDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOverviewDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainSourceDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Second Brain, step 1: what Beyou understands about a person, and how they steer it.
 *
 * <p>The page is one read ({@link #overview}) that writes nothing. The interview, the profile
 * and the switches are small writes in {@link SecondBrainStore}. Three routes call a model:
 * {@link #refill}, {@link #answer} and {@link #refreshInsight}.
 *
 * <p><b>Those three are not {@code @Transactional}, and must not become so.</b> A model call
 * can take most of a minute, and a transaction held across it keeps one of the pool's ten
 * connections for that long. Each one reads what the prompt needs in a short transaction, calls
 * the model with none open, and writes what came back in another short one. The store's
 * methods join a transaction the caller already holds, so marking one of these methods, or the
 * class, would quietly bring the long hold back.
 *
 * <p>Whether a model exists at all is {@link LlmProperties#isConfigured()}. Without one the
 * page still works: seeds and check-ins are asked, an answer to a profile question becomes a
 * fact as written, and only the overview refresh answers {@code AI_UNAVAILABLE}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SecondBrainService {

    private final SecondBrainStore store;
    private final SecondBrainSignals signals;
    private final SecondBrainAi ai;
    private final LlmProperties llm;

    /**
     * Accounts whose follow-ups are being written right now.
     *
     * <p>The page asks for a refill when it mounts, and a page that mounts twice would pay for
     * two model calls to keep one answer: the store drops the second set (see
     * {@link SecondBrainStore#addFollowUps}), but only after it was generated. In memory, so it
     * holds on one instance only; the store's rule is what holds across instances.
     */
    private final Set<UUID> writingFollowUps = ConcurrentHashMap.newKeySet();

    /** The whole page. Reads only, in one transaction. */
    @Transactional(readOnly = true)
    public SecondBrainOverviewDTO overview(User user) {
        UUID userId = user.getId();
        Set<SecondBrainSource> enabled = store.enabledSources(userId);
        SecondBrainSignals.Blocks blocks = signals.blocks(user, store.today(user), enabled);
        return new SecondBrainOverviewDTO(
                store.insight(user),
                blocks.goalsAhead(),
                blocks.routine(),
                blocks.areas(),
                store.openQuestions(userId),
                store.facts(userId),
                store.sources(userId),
                llm.isConfigured());
    }

    public List<SecondBrainSourceDTO> setSource(User user, SecondBrainSource source, boolean enabled) {
        return store.setSource(user.getId(), source, enabled);
    }

    public SecondBrainFactDTO createFact(User user, SecondBrainFactRequestDTO request) {
        return store.createFact(user.getId(), request.kind(), request.text());
    }

    public SecondBrainFactDTO editFact(User user, UUID factId, SecondBrainFactRequestDTO request) {
        return store.editFact(user.getId(), factId, request.kind(), request.text());
    }

    public void deleteFact(User user, UUID factId) {
        store.deleteFact(user.getId(), factId);
    }

    /**
     * Tops the interview up and answers the OPEN questions.
     *
     * <p>The seeds and the daily check-in need no model and are written first. Then, when the
     * person has started the AI side of the page (see {@link #aiInUse}), there is room, and
     * none was written in the last six hours, the model is asked for follow-ups. A model that
     * fails costs nothing but a warning in the log: the person still gets the questions that
     * were already there.
     */
    public List<SecondBrainQuestionDTO> refill(User user) {
        UUID userId = user.getId();
        RefillPlan plan = store.seed(user);

        if (plan.followUpRoom() > 0 && aiInUse(userId) && writingFollowUps.add(userId)) {
            try {
                LocalDate today = store.today(user);
                String data = signals.promptData(user, today, plan.enabled(), store.facts(userId));
                List<String> questions = ai.followUps(
                        user, today, data, store.recentQuestions(userId), plan.followUpRoom());
                store.addFollowUps(userId, questions);
            } catch (RuntimeException e) {
                log.warn("Second Brain follow-up questions skipped for user {}: {}", userId, e.getMessage());
            } finally {
                writingFollowUps.remove(userId);
            }
        }
        return store.openQuestions(userId);
    }

    /**
     * Closes the question with the answer, then learns from it.
     *
     * <p>The close commits before the model is asked anything, so the answer is kept whatever
     * happens next and a double submit gets {@code SECOND_BRAIN_QUESTION_CLOSED} instead of a
     * second set of facts.
     *
     * <p>A profile question always leaves something: the model's split of the answer, or, with
     * no model in use or a failed call or an empty split, the answer itself cut to one fact. A
     * check-in or a follow-up leaves only what the model judged lasting, which is often nothing.
     */
    public SecondBrainAnswerResultDTO answer(User user, UUID questionId, SecondBrainAnswerRequestDTO request) {
        UUID userId = user.getId();
        SecondBrainQuestionDTO closed = store.answer(userId, questionId, request.answer());
        List<LearnedFact> learned = learn(user, closed);
        return new SecondBrainAnswerResultDTO(closed, store.saveLearned(userId, learned));
    }

    private List<LearnedFact> learn(User user, SecondBrainQuestionDTO closed) {
        SecondBrainFactKind profileKind = closed.kind().factKind();
        List<LearnedFact> learned = List.of();
        if (aiInUse(user.getId())) {
            try {
                List<SecondBrainFactDTO> known = store.facts(user.getId());
                LocalDate today = store.today(user);
                learned = profileKind != null
                        ? ai.profileFacts(user, today, profileKind, closed.text(), closed.answer(), known)
                        : ai.lastingFacts(user, today, closed.text(), closed.answer(), known);
            } catch (RuntimeException e) {
                log.warn("Second Brain could not read facts out of an answer for user {}: {}",
                        user.getId(), e.getMessage());
            }
        }
        if (profileKind != null && learned.isEmpty()) {
            // Cut to size by the store, like every learned fact.
            return List.of(new LearnedFact(profileKind, closed.answer()));
        }
        return learned;
    }

    /**
     * Whether this account's data may go to the model without the person pressing anything.
     *
     * <p>Only once they have asked for an overview. That request is made from a screen that
     * says the text goes to an external AI provider, so it is the moment the person agreed to
     * it. Until then the page works with no model at all: the starter questions and the
     * check-in need none, and an answer is kept as the person wrote it. The rule lives here,
     * on the server, so a client cannot start the AI side by accident, and a new client
     * cannot forget to ask.
     */
    private boolean aiInUse(UUID userId) {
        return llm.isConfigured() && store.hasInsight(userId);
    }

    public SecondBrainQuestionDTO skip(User user, UUID questionId) {
        return store.skip(user.getId(), questionId);
    }

    /**
     * Writes the overview again from the enabled sources and the profile.
     *
     * @throws BusinessException {@link ErrorKey#AI_UNAVAILABLE} with no model configured, or
     *                           when the model failed after its one retry
     */
    public SecondBrainInsightDTO refreshInsight(User user) {
        if (!llm.isConfigured()) {
            throw SecondBrainAi.unavailable();
        }
        UUID userId = user.getId();
        LocalDate today = store.today(user);
        String data = signals.promptData(user, today, store.enabledSources(userId), store.facts(userId))
                + SecondBrainSignals.recentAnswers(store.recentAnswers(userId));
        SecondBrainAi.Overview overview = ai.insight(user, today, data);
        return store.saveInsight(userId, overview.verdict(), overview.text());
    }
}
