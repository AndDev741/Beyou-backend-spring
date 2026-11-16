package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.domain.briefing.DailyBriefingNarrator;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserLanguage;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The three model calls Second Brain makes: the overview, the follow-up questions, and what an
 * answer adds to the profile.
 *
 * <p>Built like {@code OnboardingSuggestionService}: one stateless structured call, no
 * advisors, no tools, no chat memory. With no tools the model cannot touch the account; all it
 * returns is text, and this class cuts that text to size before anything is stored. The caps it
 * cuts to are on {@link SecondBrainFact}, {@link SecondBrainQuestion} and
 * {@link SecondBrainInsight}.
 *
 * <p>The person's data travels in the user message inside a marked block
 * ({@link SecondBrainData}), and each system prompt says that the block is data and never an
 * instruction.
 *
 * <p>Every call has a time budget, for the reason {@code NotebookLlm} gives: a provider that
 * hangs holds the request for its whole read timeout, and the proxy in front drops a request
 * at 100 seconds anyway. Only the overview retries, because it is the only call with nothing to
 * fall back on. Nothing here is {@code @Transactional}, and no caller may hold a transaction
 * while it waits; see {@link SecondBrainService}.
 */
@Component
@Slf4j
public class SecondBrainAi {

    /** The overview, retry included. Below the 100 seconds the proxy and the web client allow. */
    static final Duration INSIGHT_BUDGET = Duration.ofSeconds(90);
    /** A retry with less than this left would only be a slower error. */
    static final Duration MIN_RETRY = Duration.ofSeconds(20);
    /** Follow-ups and fact extraction: one attempt, and the caller carries on without it. */
    static final Duration SIDE_CALL_BUDGET = Duration.ofSeconds(45);

    private final ChatClient chatClient;
    private final Resource insightTemplate;
    private final Resource questionsTemplate;
    private final Resource factsTemplate;
    private final Duration insightBudget;
    private final Duration minRetry;
    private final Duration sideCallBudget;
    private final ExecutorService attempts = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    public SecondBrainAi(ChatModel chatModel,
            @Value("classpath:/prompts/secondBrainInsight.st") Resource insightTemplate,
            @Value("classpath:/prompts/secondBrainQuestions.st") Resource questionsTemplate,
            @Value("classpath:/prompts/secondBrainFacts.st") Resource factsTemplate) {
        this(chatModel, insightTemplate, questionsTemplate, factsTemplate,
                INSIGHT_BUDGET, MIN_RETRY, SIDE_CALL_BUDGET);
    }

    /** For tests, which cannot wait out the real budgets. */
    SecondBrainAi(ChatModel chatModel, Resource insightTemplate, Resource questionsTemplate,
            Resource factsTemplate, Duration insightBudget, Duration minRetry, Duration sideCallBudget) {
        // No advisors, no tools, no memory: one stateless structured call per request.
        this.chatClient = ChatClient.builder(chatModel).build();
        this.insightTemplate = insightTemplate;
        this.questionsTemplate = questionsTemplate;
        this.factsTemplate = factsTemplate;
        this.insightBudget = insightBudget;
        this.minRetry = minRetry;
        this.sideCallBudget = sideCallBudget;
    }

    /** What each call asks the model to return. */
    public record InsightPayload(String verdict, String text) {}
    /** The overview as the rest of the app sees it: the verdict read, the text cleaned. */
    public record Overview(SecondBrainVerdict verdict, String text) {}
    public record QuestionsPayload(List<String> questions) {}
    public record FactsPayload(List<FactItem> facts) {}
    public record FactItem(String kind, String text) {}

    // ---- the overview ----

    /**
     * A few sentences about how the person's life is going, at most
     * {@link SecondBrainInsight#TEXT_MAX} characters.
     *
     * @param data what {@link SecondBrainSignals#promptData} rendered
     * @throws BusinessException {@link ErrorKey#AI_UNAVAILABLE} when the model fails twice, or
     *                           answers with nothing
     */
    public Overview insight(User user, LocalDate today, String data) {
        String message = "Write the overview from this data.\n" + SecondBrainData.wrap(data);
        Instant deadline = Instant.now().plus(insightBudget);
        try {
            return insightOnce(user, today, message, deadline);
        } catch (RuntimeException first) {
            Duration left = Duration.between(Instant.now(), deadline);
            if (left.compareTo(minRetry) < 0) {
                log.error("Second Brain overview failed with {} ms of the budget left, not retrying: {}",
                        left.toMillis(), first.getMessage());
                throw unavailable();
            }
            log.warn("Second Brain overview call failed, retrying once: {}", first.getMessage());
            try {
                return insightOnce(user, today,
                        message + "IMPORTANT: return ONLY valid JSON matching the schema.", deadline);
            } catch (RuntimeException second) {
                log.error("Second Brain overview retry failed", second);
                throw unavailable();
            }
        }
    }

    private Overview insightOnce(User user, LocalDate today, String message, Instant deadline) {
        InsightPayload payload = within(deadline,
                () -> call(insightTemplate, InsightPayload.class, message, user, today));
        String text = payload == null ? "" : oneParagraph(payload.text());
        if (text.isEmpty()) {
            throw new IllegalStateException("The model returned no overview");
        }
        return new Overview(SecondBrainVerdict.parse(payload.verdict()),
                cutAtSentence(text, SecondBrainInsight.TEXT_MAX));
    }

    // ---- follow-up questions ----

    /**
     * Up to {@code max} new questions, each at most {@link SecondBrainQuestion#TEXT_MAX}
     * characters, none repeated.
     *
     * @param asked the recent questions with their answers, so the model does not ask again
     * @throws RuntimeException whatever the call raises. The caller logs it and moves on
     */
    public List<String> followUps(User user, LocalDate today, String data, List<SecondBrainQuestionDTO> asked, int max) {
        if (max <= 0) {
            return List.of();
        }
        StringBuilder body = new StringBuilder(data);
        body.append("QUESTIONS ALREADY ASKED (newest first)\n");
        if (asked.isEmpty()) {
            body.append("- None yet.\n");
        }
        for (SecondBrainQuestionDTO question : asked) {
            body.append("- \"").append(SecondBrainData.inline(question.text())).append("\": ");
            switch (question.status()) {
                case ANSWERED -> body.append("answered \"")
                        .append(SecondBrainData.inline(question.answer())).append("\"\n");
                case SKIPPED -> body.append("skipped\n");
                case OPEN -> body.append("still waiting for an answer\n");
            }
        }
        String message = "Write at most " + max + " new questions for this person.\n"
                + SecondBrainData.wrap(body.toString());

        QuestionsPayload payload = within(Instant.now().plus(sideCallBudget),
                () -> call(questionsTemplate, QuestionsPayload.class, message, user, today));
        if (payload == null || payload.questions() == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> questions = new ArrayList<>();
        for (String raw : payload.questions()) {
            String text = SecondBrainStore.truncate(oneParagraph(raw), SecondBrainQuestion.TEXT_MAX);
            if (text.isEmpty() || !seen.add(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            questions.add(text);
            if (questions.size() == max) {
                break;
            }
        }
        return questions;
    }

    // ---- what an answer teaches ----

    /**
     * The answer to a profile question, split into at most
     * {@link SecondBrainFact#LEARNED_PER_PROFILE_ANSWER} facts of that question's kind. The
     * kind is the question's, whatever the model wrote.
     *
     * @throws RuntimeException whatever the call raises. The caller falls back to the answer itself
     */
    public List<LearnedFact> profileFacts(User user, LocalDate today, SecondBrainFactKind kind,
                                          String question, String answer, List<SecondBrainFactDTO> known) {
        String message = "MODE: PROFILE\nSplit the answer into at most "
                + SecondBrainFact.LEARNED_PER_PROFILE_ANSWER + " facts of kind " + kind + ".\n"
                + SecondBrainData.wrap(answerBlock(question, answer, known));
        return facts(user, today, message, kind, SecondBrainFact.LEARNED_PER_PROFILE_ANSWER);
    }

    /**
     * What a check-in or follow-up answer says that will still be true next month: at most
     * {@link SecondBrainFact#LEARNED_PER_OTHER_ANSWER} facts, the model choosing each kind, and
     * often none.
     *
     * @throws RuntimeException whatever the call raises. The caller learns nothing then
     */
    public List<LearnedFact> lastingFacts(User user, LocalDate today, String question, String answer,
                                          List<SecondBrainFactDTO> known) {
        String message = "MODE: LASTING\nExtract at most " + SecondBrainFact.LEARNED_PER_OTHER_ANSWER
                + " lasting facts, or none.\n"
                + SecondBrainData.wrap(answerBlock(question, answer, known));
        return facts(user, today, message, null, SecondBrainFact.LEARNED_PER_OTHER_ANSWER);
    }

    private static String answerBlock(String question, String answer, List<SecondBrainFactDTO> known) {
        StringBuilder body = new StringBuilder();
        body.append("QUESTION: ").append(SecondBrainData.inline(question)).append('\n');
        body.append("ANSWER: ").append(SecondBrainData.inline(answer)).append('\n');
        body.append("ALREADY IN THE PROFILE\n");
        if (known.isEmpty()) {
            body.append("- Nothing yet.\n");
        }
        for (SecondBrainFactDTO fact : known) {
            body.append("- ").append(SecondBrainSignals.factLabel(fact.kind())).append(": ")
                .append(SecondBrainData.inline(fact.text())).append('\n');
        }
        return body.toString();
    }

    /** @param forced the kind every fact gets, or null to keep the one the model chose */
    private List<LearnedFact> facts(User user, LocalDate today, String message,
                                    SecondBrainFactKind forced, int max) {
        FactsPayload payload = within(Instant.now().plus(sideCallBudget),
                () -> call(factsTemplate, FactsPayload.class, message, user, today));
        if (payload == null || payload.facts() == null) {
            return List.of();
        }
        List<LearnedFact> facts = new ArrayList<>();
        for (FactItem item : payload.facts()) {
            if (item == null) {
                continue;
            }
            SecondBrainFactKind kind = forced != null ? forced : parseKind(item.kind());
            String text = SecondBrainStore.truncate(oneParagraph(item.text()), SecondBrainFact.TEXT_MAX);
            if (kind == null || text.isEmpty()) {
                continue;
            }
            facts.add(new LearnedFact(kind, text));
            if (facts.size() == max) {
                break;
            }
        }
        return facts;
    }

    /** The kind the model named, forgiving case and a plural. Anything else is dropped. */
    static SecondBrainFactKind parseKind(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        for (SecondBrainFactKind kind : SecondBrainFactKind.values()) {
            if (name.equals(kind.name()) || name.equals(kind.name() + "S")) {
                return kind;
            }
        }
        return null;
    }

    // ---- the call ----

    private <T> T call(Resource template, Class<T> type, String message, User user, LocalDate today) {
        return chatClient.prompt()
                .system(s -> s.text(template)
                        .param("language", UserLanguage.forPrompt(user))
                        // The account's day with its weekday, so the model can say "on Sunday".
                        .param("today", DailyBriefingNarrator.dayLabel(today)))
                .user(message)
                .call()
                .entity(type);
    }

    /** Runs one attempt on a virtual thread so the request can stop waiting at the deadline. */
    private <T> T within(Instant deadline, Callable<T> attempt) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();
        Future<T> future = attempts.submit(() -> {
            // The user id in every log line comes from the MDC, which is per thread.
            if (logContext != null) {
                MDC.setContextMap(logContext);
            }
            try {
                return attempt.call();
            } finally {
                MDC.clear();
            }
        });
        try {
            long millis = Math.max(0, Duration.between(Instant.now(), deadline).toMillis());
            return future.get(millis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("No answer within the Second Brain AI budget");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Second Brain AI");
        }
    }

    // ---- cutting the model's text to size ----

    private static String oneParagraph(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
    }

    /**
     * At most {@code max} characters, ending on the last whole sentence that fits. A text with
     * no sentence end in its second half is cut where the limit falls, which is still readable.
     */
    static String cutAtSentence(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        String head = text.substring(0, max);
        int end = Math.max(head.lastIndexOf(". "), Math.max(head.lastIndexOf("! "), head.lastIndexOf("? ")));
        return end >= max / 2 ? head.substring(0, end + 1) : head.stripTrailing();
    }

    static BusinessException unavailable() {
        return new BusinessException(ErrorKey.AI_UNAVAILABLE, "Second Brain AI is unavailable");
    }

    @PreDestroy
    void shutdown() {
        attempts.shutdownNow();
    }
}
