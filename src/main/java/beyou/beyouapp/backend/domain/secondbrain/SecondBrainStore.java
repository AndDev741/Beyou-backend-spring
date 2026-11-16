package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainInsightDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainSourceDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserLanguage;
import lombok.RequiredArgsConstructor;

/**
 * Every read and write Second Brain makes on its own tables, each in a short transaction.
 *
 * <p>A bean of its own, apart from {@link SecondBrainService}, because the service calls a
 * model between a read and a write and must not hold a transaction while it waits. The same
 * shape as {@code DailyBriefingWrites} beside {@code DailyBriefingService}.
 *
 * <p>The rules that live here and nowhere else:
 * <ol>
 *   <li><b>A missing switch is the default.</b> {@link #effectiveSources} is the only reader.</li>
 *   <li><b>A fact or a question is only reachable by its owner.</b> Every lookup carries the
 *       owner's id, so someone else's row and a missing one get the same 404.</li>
 *   <li><b>At most {@link SecondBrainFact#MAX_PER_USER} facts and
 *       {@link SecondBrainQuestion#MAX_OPEN} open questions.</b> Counted under
 *       {@link SecondBrainFactRepository#lockProfile}, so two writes at once cannot both fit.</li>
 *   <li><b>A question closes once.</b> Answer and skip read it under a row lock.</li>
 * </ol>
 *
 * <p>Not cached, like quick notes and mood: every read is one indexed query over a few dozen
 * short rows, and a switch flipped on the phone has to show on the web at the next read.
 */
@Component
@RequiredArgsConstructor
public class SecondBrainStore {

    /** How long after a follow-up was written before the model is asked for more. */
    static final Duration FOLLOW_UP_PAUSE = Duration.ofHours(6);
    /** How many past questions the model is shown so it does not repeat itself. */
    static final int RECENT_QUESTIONS = 15;
    /** How many of the latest answers the overview reads. */
    static final int RECENT_ANSWERS = 8;

    private static final List<SecondBrainQuestionKind> SEEDS = List.of(
            SecondBrainQuestionKind.PROFILE_OBJECTIVES,
            SecondBrainQuestionKind.PROFILE_LIKES,
            SecondBrainQuestionKind.PROFILE_ABOUT);

    private final SecondBrainSourceSettingRepository sourceRepository;
    private final SecondBrainFactRepository factRepository;
    private final SecondBrainQuestionRepository questionRepository;
    private final SecondBrainInsightRepository insightRepository;
    private final DiaryRoutineRepository diaryRoutineRepository;
    private final SecondBrainMapper mapper;

    /**
     * Not injected, for the same reason as {@code QuickNoteService}'s: there is no {@code Clock}
     * bean. Non-final so Lombok leaves it out of the constructor; tests replace it to fix "now".
     */
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The account's own day, on this store's clock, so "today" and "now" cannot disagree. */
    public LocalDate today(User user) {
        return UserDateResolver.today(user, clock);
    }

    // ---- sources ----

    /** All six switches in enum order, each with its stored value or its default. */
    @Transactional(readOnly = true)
    public Map<SecondBrainSource, Boolean> effectiveSources(UUID userId) {
        Map<SecondBrainSource, Boolean> effective = new EnumMap<>(SecondBrainSource.class);
        for (SecondBrainSource source : SecondBrainSource.values()) {
            effective.put(source, source.enabledByDefault());
        }
        sourceRepository.findByUserId(userId)
                .forEach(row -> effective.put(row.getSource(), row.isEnabled()));
        return effective;
    }

    @Transactional(readOnly = true)
    public Set<SecondBrainSource> enabledSources(UUID userId) {
        Set<SecondBrainSource> enabled = EnumSet.noneOf(SecondBrainSource.class);
        effectiveSources(userId).forEach((source, on) -> {
            if (on) {
                enabled.add(source);
            }
        });
        return enabled;
    }

    @Transactional(readOnly = true)
    public List<SecondBrainSourceDTO> sources(UUID userId) {
        return effectiveSources(userId).entrySet().stream()
                .map(entry -> new SecondBrainSourceDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Transactional
    public List<SecondBrainSourceDTO> setSource(UUID userId, SecondBrainSource source, boolean enabled) {
        sourceRepository.upsert(userId, source.name(), enabled, now());
        return sources(userId);
    }

    // ---- facts ----

    /** The profile, oldest first. */
    @Transactional(readOnly = true)
    public List<SecondBrainFactDTO> facts(UUID userId) {
        return factRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream()
                .map(mapper::toDTO).toList();
    }

    /**
     * The profile for the assistant chat's system prompt, or an empty string when there is
     * none. This is the general layer: what every conversation starts from, whatever it is
     * about. It rides on every agent turn, which makes the fact caps a cap on that cost too.
     */
    @Transactional(readOnly = true)
    public String profileForChat(UUID userId) {
        return SecondBrainSignals.profileLines(facts(userId));
    }

    /**
     * A fact the person typed.
     *
     * @throws BusinessException {@link ErrorKey#SECOND_BRAIN_FACT_LIMIT_REACHED} with the
     *                           profile full
     */
    @Transactional
    public SecondBrainFactDTO createFact(UUID userId, SecondBrainFactKind kind, String text) {
        lockProfile(userId);
        if (factRepository.countByUserId(userId) >= SecondBrainFact.MAX_PER_USER) {
            throw new BusinessException(ErrorKey.SECOND_BRAIN_FACT_LIMIT_REACHED,
                    "The profile holds at most " + SecondBrainFact.MAX_PER_USER
                            + " facts; delete one to make room");
        }
        return mapper.toDTO(factRepository.save(
                newFact(userId, kind, text.strip(), SecondBrainFactOrigin.USER, now())));
    }

    /** New words and kind. The origin stays: a learned fact the person corrected was still learned. */
    @Transactional
    public SecondBrainFactDTO editFact(UUID userId, UUID factId, SecondBrainFactKind kind, String text) {
        SecondBrainFact fact = ownedFact(userId, factId);
        fact.setKind(kind);
        fact.setText(text.strip());
        fact.setUpdatedAt(now());
        return mapper.toDTO(fact);
    }

    @Transactional
    public void deleteFact(UUID userId, UUID factId) {
        factRepository.delete(ownedFact(userId, factId));
    }

    /**
     * Adds what an answer taught, as many as fit under the limit, in the order given. A fact the
     * profile already holds word for word is left out, so answering two questions the same way
     * does not fill the profile with copies.
     *
     * @return the facts that were saved, possibly none
     */
    @Transactional
    public List<SecondBrainFactDTO> saveLearned(UUID userId, List<LearnedFact> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        lockProfile(userId);
        List<SecondBrainFact> existing = factRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId);
        Set<String> known = new HashSet<>();
        existing.forEach(fact -> known.add(duplicateKey(fact.getKind(), fact.getText())));

        int room = SecondBrainFact.MAX_PER_USER - existing.size();
        Instant now = now();
        List<SecondBrainFactDTO> saved = new ArrayList<>();
        for (LearnedFact candidate : candidates) {
            if (room <= 0) {
                break;
            }
            String text = truncate(candidate.text() == null ? "" : candidate.text().strip(),
                    SecondBrainFact.TEXT_MAX);
            if (candidate.kind() == null || text.isEmpty()
                    || !known.add(duplicateKey(candidate.kind(), text))) {
                continue;
            }
            // A microsecond apart, so "oldest first" keeps the order the answer gave them in.
            Instant at = now.plus(saved.size(), ChronoUnit.MICROS);
            saved.add(mapper.toDTO(factRepository.save(
                    newFact(userId, candidate.kind(), text, SecondBrainFactOrigin.ANSWER, at))));
            room--;
        }
        return saved;
    }

    // ---- questions ----

    /** What the page shows: the OPEN questions, oldest first. */
    @Transactional(readOnly = true)
    public List<SecondBrainQuestionDTO> openQuestions(UUID userId) {
        return questionRepository
                .findByUserIdAndStatusOrderByCreatedAtAscIdAsc(userId, SecondBrainQuestionStatus.OPEN)
                .stream().map(mapper::toDTO).toList();
    }

    /** The last {@link #RECENT_QUESTIONS} questions, newest first, whatever became of them. */
    @Transactional(readOnly = true)
    public List<SecondBrainQuestionDTO> recentQuestions(UUID userId) {
        return questionRepository.findByUserIdOrderByCreatedAtDescIdAsc(userId, Limit.of(RECENT_QUESTIONS))
                .stream().map(mapper::toDTO).toList();
    }

    /**
     * What a refill decided once the questions that need no model were written.
     *
     * @param followUpRoom how many follow-ups the model may be asked for: the open slots left,
     *                     or zero while {@link #FOLLOW_UP_PAUSE} has not passed
     * @param enabled      the sources in effect, read in the same transaction
     */
    public record RefillPlan(int followUpRoom, Set<SecondBrainSource> enabled) {}

    /**
     * The part of a refill that needs no model: the seeds, then the daily check-in, never past
     * {@link SecondBrainQuestion#MAX_OPEN} open questions.
     *
     * <p>A seed is asked when the profile has no fact of its kind and that seed was never asked
     * before, answered or skipped. The check-in is asked once per day of the account's own
     * calendar, when ROUTINES is on and a routine runs that day.
     */
    @Transactional
    public RefillPlan seed(User user) {
        UUID userId = user.getId();
        lockProfile(userId);

        Instant now = now();
        LocalDate today = today(user);
        String language = UserLanguage.forPrompt(user);
        int open = (int) questionRepository.countByUserIdAndStatus(userId, SecondBrainQuestionStatus.OPEN);
        int asked = 0;

        Set<SecondBrainFactKind> known = EnumSet.noneOf(SecondBrainFactKind.class);
        factRepository.findByUserIdOrderByCreatedAtAscIdAsc(userId).forEach(fact -> known.add(fact.getKind()));
        for (SecondBrainQuestionKind seed : SEEDS) {
            if (open >= SecondBrainQuestion.MAX_OPEN) {
                break;
            }
            if (known.contains(seed.factKind()) || questionRepository.existsByUserIdAndKind(userId, seed)) {
                continue;
            }
            // A microsecond apart, so "oldest first" keeps the order they were asked in.
            ask(userId, seed, seed.fixedText(language), now.plus(asked++, ChronoUnit.MICROS));
            open++;
        }

        Set<SecondBrainSource> enabled = enabledSources(userId);
        Instant dayStart = today.atStartOfDay(UserDateResolver.zoneOf(user)).toInstant();
        if (open < SecondBrainQuestion.MAX_OPEN
                && enabled.contains(SecondBrainSource.ROUTINES)
                && !questionRepository.existsByUserIdAndKindAndCreatedAtGreaterThanEqual(
                        userId, SecondBrainQuestionKind.CHECK_IN, dayStart)
                && routineRunsOn(userId, today)) {
            ask(userId, SecondBrainQuestionKind.CHECK_IN,
                    SecondBrainQuestionKind.CHECK_IN.fixedText(language), now.plus(asked, ChronoUnit.MICROS));
            open++;
        }

        int room = followUpPaused(userId, now) ? 0 : SecondBrainQuestion.MAX_OPEN - open;
        return new RefillPlan(room, enabled);
    }

    /**
     * Stores the follow-ups the model wrote, as many as still fit.
     *
     * <p>The room and the pause are read again here, under the lock. The model was called with
     * no transaction open, so another refill may have written its own follow-ups meanwhile; the
     * second one to arrive then writes nothing.
     */
    @Transactional
    public void addFollowUps(UUID userId, List<String> texts) {
        if (texts.isEmpty()) {
            return;
        }
        lockProfile(userId);
        Instant now = now();
        if (followUpPaused(userId, now)) {
            return;
        }
        int room = SecondBrainQuestion.MAX_OPEN
                - (int) questionRepository.countByUserIdAndStatus(userId, SecondBrainQuestionStatus.OPEN);

        Set<String> alreadyAsked = new HashSet<>();
        questionRepository.findByUserIdOrderByCreatedAtDescIdAsc(userId, Limit.of(RECENT_QUESTIONS))
                .forEach(question -> alreadyAsked.add(question.getText().toLowerCase(Locale.ROOT)));

        int asked = 0;
        for (String raw : texts) {
            if (room <= 0) {
                break;
            }
            String text = truncate(raw == null ? "" : raw.strip(), SecondBrainQuestion.TEXT_MAX);
            if (text.isEmpty() || !alreadyAsked.add(text.toLowerCase(Locale.ROOT))) {
                continue;
            }
            ask(userId, SecondBrainQuestionKind.FOLLOW_UP, text, now.plus(asked++, ChronoUnit.MICROS));
            room--;
        }
    }

    /**
     * Closes the question with the person's answer, in a transaction that ends before anything
     * is learned from it. The answer is kept whatever the model does next, and a second submit
     * finds the question closed.
     *
     * @throws BusinessException {@link ErrorKey#SECOND_BRAIN_QUESTION_NOT_FOUND} when it is not
     *                           the caller's; {@link ErrorKey#SECOND_BRAIN_QUESTION_CLOSED} when
     *                           it was already answered or skipped
     */
    @Transactional
    public SecondBrainQuestionDTO answer(UUID userId, UUID questionId, String answer) {
        SecondBrainQuestion question = openQuestion(userId, questionId);
        question.setStatus(SecondBrainQuestionStatus.ANSWERED);
        question.setAnswer(answer.strip());
        question.setAnsweredAt(now());
        return mapper.toDTO(question);
    }

    /** Closes the question without an answer. Same refusals as {@link #answer}. */
    @Transactional
    public SecondBrainQuestionDTO skip(UUID userId, UUID questionId) {
        SecondBrainQuestion question = openQuestion(userId, questionId);
        question.setStatus(SecondBrainQuestionStatus.SKIPPED);
        return mapper.toDTO(question);
    }

    // ---- insight ----

    /**
     * The overview on record. Stale means it was written before the account's current day:
     * the page rewrites it on the first visit of a day, so the verdict a person reads is
     * never yesterday's. By the account's own calendar and not by hours, so "today" means the
     * same here as it does on the routine.
     */
    @Transactional(readOnly = true)
    public SecondBrainInsightDTO insight(User user) {
        LocalDate today = today(user);
        ZoneId zone = UserDateResolver.zoneOf(user);
        return insightRepository.findById(user.getId())
                .map(row -> new SecondBrainInsightDTO(row.getText(), row.getVerdict(), row.getGeneratedAt(),
                        row.getGeneratedAt().atZone(zone).toLocalDate().isBefore(today)))
                .orElseGet(SecondBrainInsightDTO::none);
    }

    /** Whether the account ever had an overview written, which is how the AI side is started. */
    @Transactional(readOnly = true)
    public boolean hasInsight(UUID userId) {
        return insightRepository.existsById(userId);
    }

    /** Replaces the account's overview with a freshly generated one. */
    @Transactional
    public SecondBrainInsightDTO saveInsight(UUID userId, SecondBrainVerdict verdict, String text) {
        Instant now = now();
        insightRepository.upsert(userId, text, verdict.name(), now);
        return new SecondBrainInsightDTO(text, verdict, now, false);
    }

    /**
     * What the person answered lately, newest first, for the overview to read. Answered
     * questions only: a skipped or open one says nothing about how things are going.
     */
    @Transactional(readOnly = true)
    public List<SecondBrainQuestionDTO> recentAnswers(UUID userId) {
        return recentQuestions(userId).stream()
                .filter(question -> question.status() == SecondBrainQuestionStatus.ANSWERED)
                .limit(RECENT_ANSWERS)
                .toList();
    }

    // ---- shared ----

    private void lockProfile(UUID userId) {
        factRepository.lockProfile("second-brain:" + userId);
    }

    private boolean followUpPaused(UUID userId, Instant now) {
        return questionRepository.existsByUserIdAndKindAndCreatedAtGreaterThanEqual(
                userId, SecondBrainQuestionKind.FOLLOW_UP, now.minus(FOLLOW_UP_PAUSE));
    }

    private boolean routineRunsOn(UUID userId, LocalDate day) {
        return diaryRoutineRepository.findAllByUserId(userId).stream()
                .anyMatch(routine -> ScheduledOnDayResolver.runsOn(routine, day));
    }

    private void ask(UUID userId, SecondBrainQuestionKind kind, String text, Instant at) {
        SecondBrainQuestion question = new SecondBrainQuestion();
        question.setUserId(userId);
        question.setKind(kind);
        question.setText(text);
        question.setStatus(SecondBrainQuestionStatus.OPEN);
        question.setCreatedAt(at);
        questionRepository.save(question);
    }

    private static SecondBrainFact newFact(UUID userId, SecondBrainFactKind kind, String text,
                                           SecondBrainFactOrigin origin, Instant at) {
        SecondBrainFact fact = new SecondBrainFact();
        fact.setUserId(userId);
        fact.setKind(kind);
        fact.setText(text);
        fact.setOrigin(origin);
        fact.setCreatedAt(at);
        fact.setUpdatedAt(at);
        return fact;
    }

    private SecondBrainFact ownedFact(UUID userId, UUID factId) {
        return factRepository.findByIdAndUserId(factId, userId).orElseThrow(() ->
                new BusinessException(ErrorKey.SECOND_BRAIN_FACT_NOT_FOUND, "Fact not found"));
    }

    private SecondBrainQuestion openQuestion(UUID userId, UUID questionId) {
        SecondBrainQuestion question = questionRepository.lockByIdAndUserId(questionId, userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorKey.SECOND_BRAIN_QUESTION_NOT_FOUND, "Question not found"));
        if (question.getStatus() != SecondBrainQuestionStatus.OPEN) {
            throw new BusinessException(ErrorKey.SECOND_BRAIN_QUESTION_CLOSED,
                    "This question was already answered or skipped");
        }
        return question;
    }

    private static String duplicateKey(SecondBrainFactKind kind, String text) {
        return kind + ":" + text.toLowerCase(Locale.ROOT);
    }

    static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max).stripTrailing();
    }

    /** Microseconds, which is what a timestamptz keeps, so the value in memory is the value stored. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
