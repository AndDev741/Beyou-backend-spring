package beyou.beyouapp.backend.integration.secondbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.briefing.dto.GoalAhead;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.mood.MoodService;
import beyou.beyouapp.backend.domain.mood.dto.UpsertMoodEntryDTO;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.UpdateContentRequestDTO;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFact;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestion;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionRepository;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionStatus;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainService;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSignals;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSource;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainVerdict;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainStore;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAreaDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainInsightDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOverviewDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainSourceDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

/**
 * Second Brain's rules against a real Postgres: the switches and their defaults, the profile's
 * limit and ownership, what a refill asks and when, what an answer leaves behind, and what each
 * source feeds.
 *
 * <p>No model is configured in the test profile, so this is the feature as it runs without one.
 * What changes with a model, and the proof that no transaction is open while it is asked, are in
 * {@link SecondBrainModelCallIT}.
 *
 * <p>Transactional, so every case runs in one transaction and rolls back.
 */
@Transactional
class SecondBrainServiceIT extends AbstractIntegrationTest {

    /** A Wednesday, at noon UTC. The schedules below are picked against that weekday. */
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Autowired private SecondBrainService service;
    @Autowired private SecondBrainStore store;
    @Autowired private SecondBrainSignals signals;
    @Autowired private SecondBrainQuestionRepository questionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private RoutineSnapshotRepository snapshotRepository;
    @Autowired private MoodService moodService;
    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private User owner;
    private User stranger;

    @BeforeEach
    void seed() {
        owner = newUser("brain-owner", "UTC", "en");
        stranger = newUser("brain-stranger", "UTC", "en");
        setClock(NOW);
    }

    // ---- sources ----

    @Test
    @DisplayName("with no row, routines, goals and mood are on and calendar, notebook and ideas are off")
    void sourcesDefaultWithoutARow() {
        assertThat(service.overview(owner).sources()).containsExactly(
                new SecondBrainSourceDTO(SecondBrainSource.ROUTINES, true),
                new SecondBrainSourceDTO(SecondBrainSource.GOALS, true),
                new SecondBrainSourceDTO(SecondBrainSource.MOOD, true),
                new SecondBrainSourceDTO(SecondBrainSource.CALENDAR, false),
                new SecondBrainSourceDTO(SecondBrainSource.NOTEBOOK, false),
                new SecondBrainSourceDTO(SecondBrainSource.IDEAS, false));
        assertThat(rows("second_brain_sources", owner)).as("reading the defaults writes nothing").isZero();
    }

    @Test
    @DisplayName("a switch flips on its own, flips back, and is the owner's alone")
    void togglingASource() {
        List<SecondBrainSourceDTO> afterOff = service.setSource(owner, SecondBrainSource.MOOD, false);
        List<SecondBrainSourceDTO> afterOn = service.setSource(owner, SecondBrainSource.IDEAS, true);

        assertThat(afterOff).hasSize(6).contains(new SecondBrainSourceDTO(SecondBrainSource.MOOD, false));
        assertThat(afterOn).containsExactly(
                new SecondBrainSourceDTO(SecondBrainSource.ROUTINES, true),
                new SecondBrainSourceDTO(SecondBrainSource.GOALS, true),
                new SecondBrainSourceDTO(SecondBrainSource.MOOD, false),
                new SecondBrainSourceDTO(SecondBrainSource.CALENDAR, false),
                new SecondBrainSourceDTO(SecondBrainSource.NOTEBOOK, false),
                new SecondBrainSourceDTO(SecondBrainSource.IDEAS, true));

        assertThat(service.setSource(owner, SecondBrainSource.MOOD, true))
                .contains(new SecondBrainSourceDTO(SecondBrainSource.MOOD, true));
        assertThat(rows("second_brain_sources", owner)).as("one row per switch touched").isEqualTo(2);
        assertThat(store.enabledSources(stranger.getId()))
                .containsExactlyInAnyOrder(SecondBrainSource.ROUTINES, SecondBrainSource.GOALS, SecondBrainSource.MOOD);
    }

    // ---- the page ----

    @Test
    @DisplayName("a new account's page is empty, and reading it writes nothing")
    void overviewOfANewAccount() {
        SecondBrainOverviewDTO page = service.overview(owner);

        assertThat(page.insight()).isEqualTo(SecondBrainInsightDTO.none());
        assertThat(page.goalsAhead()).isEmpty();
        assertThat(page.routine()).as("ROUTINES is on, there is just no history").isNotNull();
        assertThat(page.routine().thisWeekPercent()).isNull();
        assertThat(page.routine().previousWeekPercent()).isNull();
        assertThat(page.routine().slipping()).isEmpty();
        assertThat(page.routine().steady()).isEmpty();
        assertThat(page.areas()).isEmpty();
        assertThat(page.questions()).isEmpty();
        assertThat(page.facts()).isEmpty();
        assertThat(page.aiAvailable()).as("no LLM endpoint in the test profile").isFalse();

        for (String table : List.of("second_brain_sources", "second_brain_facts",
                "second_brain_questions", "second_brain_insights")) {
            assertThat(rows(table, owner)).as(table).isZero();
        }
    }

    @Test
    @DisplayName("areas count each asked item under every category it has, skips and deleted items left out")
    void areasAndRoutineComeFromTheSnapshots() {
        Category health = category(owner, "Health");
        Category career = category(owner, "Career");
        category(owner, "Idle");
        Habit run = habit(owner, "Run", health);
        Habit read = habit(owner, "Read", health, career);
        Task inbox = task(owner, "Clear the inbox", career);
        DiaryRoutine routine = routine(owner, run, Set.of(WeekDay.Monday));

        RoutineSnapshot yesterday = snapshot(owner, routine, YESTERDAY);
        check(yesterday, SnapshotItemType.HABIT, run.getId(), "Run", c -> c.setChecked(true));
        check(yesterday, SnapshotItemType.HABIT, read.getId(), "Read", c -> {});
        check(yesterday, SnapshotItemType.TASK, inbox.getId(), "Clear the inbox", c -> c.setChecked(true));
        // A habit deleted since: it still counts for the week, and has no category to count under.
        check(yesterday, SnapshotItemType.HABIT, UUID.randomUUID(), "Gone", c -> c.setChecked(true));
        snapshotRepository.saveAndFlush(yesterday);

        RoutineSnapshot dayBefore = snapshot(owner, routine, YESTERDAY.minusDays(1));
        check(dayBefore, SnapshotItemType.HABIT, run.getId(), "Run", c -> c.setSkipped(true));
        check(dayBefore, SnapshotItemType.HABIT, read.getId(), "Read", c -> c.setChecked(true));
        check(dayBefore, SnapshotItemType.TASK, inbox.getId(), "Clear the inbox", c -> {});
        snapshotRepository.saveAndFlush(dayBefore);

        // The first day of the window, and the day before it, which is outside.
        RoutineSnapshot firstDay = snapshot(owner, routine, YESTERDAY.minusDays(13));
        check(firstDay, SnapshotItemType.HABIT, run.getId(), "Run", c -> c.setChecked(true));
        snapshotRepository.saveAndFlush(firstDay);
        RoutineSnapshot tooOld = snapshot(owner, routine, YESTERDAY.minusDays(14));
        check(tooOld, SnapshotItemType.HABIT, run.getId(), "Run", c -> {});
        check(tooOld, SnapshotItemType.HABIT, read.getId(), "Read", c -> {});
        snapshotRepository.saveAndFlush(tooOld);

        // A stranger's week must not leak into the owner's.
        Habit theirs = habit(stranger, "Theirs", category(stranger, "Health"));
        RoutineSnapshot foreign = snapshot(stranger, routine(stranger, theirs, Set.of(WeekDay.Monday)), YESTERDAY);
        check(foreign, SnapshotItemType.HABIT, theirs.getId(), "Theirs", c -> {});
        snapshotRepository.saveAndFlush(foreign);
        entityManager.clear();

        SecondBrainOverviewDTO page = service.overview(owner);

        assertThat(page.areas()).containsExactly(
                new SecondBrainAreaDTO(health.getId(), "Health", "icon", 4, 3, 75),
                new SecondBrainAreaDTO(career.getId(), "Career", "icon", 4, 2, 50));
        assertThat(page.routine().thisWeekPercent()).as("4 of the 6 asked this week").isEqualTo(67);
        assertThat(page.routine().previousWeekPercent()).isEqualTo(100);
        assertThat(page.routine().slipping()).isEmpty();
        assertThat(page.routine().steady()).isEmpty();
    }

    @Test
    @DisplayName("a source that is off gives a null routine, no areas and no goals")
    void disabledSourcesYieldEmptyBlocks() {
        Category health = category(owner, "Health");
        Habit run = habit(owner, "Run", health);
        DiaryRoutine routine = routine(owner, run, Set.of(WeekDay.Monday));
        RoutineSnapshot yesterday = snapshot(owner, routine, YESTERDAY);
        check(yesterday, SnapshotItemType.HABIT, run.getId(), "Run", c -> c.setChecked(true));
        snapshotRepository.saveAndFlush(yesterday);
        goal(owner, "Read twelve books", TODAY.plusDays(5));

        SecondBrainOverviewDTO on = service.overview(owner);
        assertThat(on.routine().thisWeekPercent()).isEqualTo(100);
        assertThat(on.areas()).extracting(SecondBrainAreaDTO::name).containsExactly("Health");
        assertThat(on.goalsAhead()).extracting(GoalAhead::name).containsExactly("Read twelve books");

        service.setSource(owner, SecondBrainSource.ROUTINES, false);
        service.setSource(owner, SecondBrainSource.GOALS, false);
        SecondBrainOverviewDTO off = service.overview(owner);

        assertThat(off.routine()).isNull();
        assertThat(off.areas()).isEmpty();
        assertThat(off.goalsAhead()).isEmpty();
    }

    // ---- what the model is given ----

    @Test
    @DisplayName("each source feeds the prompt only what it is meant to, and nothing once it is off")
    void promptDataFollowsTheSwitches() {
        String journal = "private-journal-" + UUID.randomUUID();
        moodService.upsert(owner, YESTERDAY, new UpsertMoodEntryDTO(4, journal));
        moodService.upsert(owner, YESTERDAY.minusDays(1), new UpsertMoodEntryDTO(2, journal));
        moodService.upsert(owner, YESTERDAY.minusDays(8), new UpsertMoodEntryDTO(1, journal));

        calendarTask(owner, "Dentist", TODAY.plusDays(5), false);
        calendarTask(owner, "Sign the lease", TODAY.minusDays(4), false);
        calendarTask(owner, "Already paid", TODAY.minusDays(2), true);
        calendarTask(owner, "Next quarter", TODAY.plusDays(22), false);
        calendarTask(stranger, "Not mine", TODAY.plusDays(1), false);

        quickNote(owner, "learn to juggle", false);
        quickNote(owner, "an archived idea", true);

        String pageText = "page-content-" + UUID.randomUUID();
        UUID topic = pageService.createTopic(owner,
                new CreateTopicRequestDTO("Spanish", null, null, null, null, null)).id();
        UUID verbs = boardService.addNode(owner, topic,
                new CreateNodeRequestDTO(null, "Verbs", null, null, 0.0, 0.0, null, null, null)).node().pageId();
        pageService.saveContent(owner, verbs, new UpdateContentRequestDTO(
                "[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"" + pageText
                        + "\",\"styles\":{}}]}]", null));

        goal(owner, "Read twelve books", TODAY.plusDays(5));
        List<SecondBrainFactDTO> facts = List.of(service.createFact(owner,
                new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "Cooking on Sundays")));
        entityManager.flush();
        entityManager.clear();

        String everything = signals.promptData(owner, TODAY, EnumSet.allOf(SecondBrainSource.class), facts);

        assertThat(everything).contains("ROUTINES\n- No routine history in the last two weeks.");
        assertThat(everything).contains("- Goal \"Read twelve books\": 40% done (4 of 10 books), ends in 5 days");
        assertThat(everything).contains("- This week averaged 3 over 2 days logged.");
        assertThat(everything).contains("- The week before averaged 1.");
        assertThat(everything).as("levels only, never the journal").doesNotContain(journal);
        assertThat(everything).contains("- \"Sign the lease\": overdue by 4 days.");
        assertThat(everything).contains("- \"Dentist\": in 5 days, on a Monday.");
        assertThat(everything).as("done, past the three weeks, or someone else's")
                .doesNotContain("Already paid").doesNotContain("Next quarter").doesNotContain("Not mine");
        assertThat(everything).contains("- \"Spanish\": 0 of 1 subtopics done.");
        assertThat(everything).as("titles only, never what a page says").doesNotContain(pageText);
        assertThat(everything).contains("- \"learn to juggle\"").doesNotContain("an archived idea");
        assertThat(everything).contains("- Likes: Cooking on Sundays");
        assertThat(everything).as("no date in ISO form").doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");

        String nothing = signals.promptData(owner, TODAY, EnumSet.noneOf(SecondBrainSource.class), facts);
        assertThat(nothing).as("the profile and the notes are the person's own, so no switch removes them").isEqualTo(
                "PROFILE (what the person told Beyou about themselves)\n- Likes: Cooking on Sundays\n"
                        + "NOTES (what the person told Beyou about their things: the newest few of each, oldest first)\n"
                        + "- Nothing yet.\n");

        String defaults = signals.promptData(owner, TODAY, store.enabledSources(owner.getId()), facts);
        assertThat(defaults).contains("ROUTINES\n", "GOALS\n", "MOOD (")
                .doesNotContain("CALENDAR").doesNotContain("NOTEBOOK").doesNotContain("IDEAS")
                .doesNotContain("Dentist").doesNotContain("Spanish").doesNotContain("juggle");
    }

    // ---- facts ----

    @Test
    @DisplayName("a typed fact is trimmed, USER, and can be reworded and deleted")
    void factLifecycle() {
        SecondBrainFactDTO created = service.createFact(owner,
                new SecondBrainFactRequestDTO(SecondBrainFactKind.OBJECTIVE, "  Run a half marathon by June \n"));

        assertThat(created.text()).isEqualTo("Run a half marathon by June");
        assertThat(created.kind()).isEqualTo(SecondBrainFactKind.OBJECTIVE);
        assertThat(created.origin()).isEqualTo(SecondBrainFactOrigin.USER);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);

        setClock(NOW.plusSeconds(60));
        SecondBrainFactDTO edited = service.editFact(owner, created.id(),
                new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, " Running "));

        assertThat(edited.id()).isEqualTo(created.id());
        assertThat(edited.text()).isEqualTo("Running");
        assertThat(edited.kind()).isEqualTo(SecondBrainFactKind.LIKE);
        assertThat(edited.createdAt()).isEqualTo(NOW);
        assertThat(edited.updatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(service.overview(owner).facts()).containsExactly(edited);

        service.deleteFact(owner, created.id());
        assertThat(service.overview(owner).facts()).isEmpty();
    }

    @Test
    @DisplayName("someone else's fact is a 404 on edit and delete, and stays as it was")
    void factsAreOwned() {
        SecondBrainFactDTO theirs = service.createFact(stranger, new SecondBrainFactRequestDTO(SecondBrainFactKind.ABOUT, "Lives in Porto"));

        assertErrorKey(() -> service.editFact(owner, theirs.id(),
                new SecondBrainFactRequestDTO(SecondBrainFactKind.ABOUT, "hijacked")), ErrorKey.SECOND_BRAIN_FACT_NOT_FOUND);
        assertErrorKey(() -> service.deleteFact(owner, theirs.id()), ErrorKey.SECOND_BRAIN_FACT_NOT_FOUND);
        assertErrorKey(() -> service.deleteFact(owner, UUID.randomUUID()), ErrorKey.SECOND_BRAIN_FACT_NOT_FOUND);

        assertThat(service.overview(stranger).facts()).containsExactly(theirs);
        assertThat(service.overview(owner).facts()).isEmpty();
    }

    @Test
    @DisplayName("the forty-first fact is refused, for that account only, until one is deleted")
    void factLimit() {
        List<SecondBrainFactDTO> facts = new ArrayList<>();
        for (int i = 0; i < SecondBrainFact.MAX_PER_USER; i++) {
            facts.add(service.createFact(owner, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "fact " + i)));
        }

        assertErrorKey(() -> service.createFact(owner, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "one too many")),
                ErrorKey.SECOND_BRAIN_FACT_LIMIT_REACHED);
        assertThat(service.createFact(stranger, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "theirs")).text())
                .isEqualTo("theirs");

        service.deleteFact(owner, facts.get(0).id());
        assertThat(service.createFact(owner, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "fits now")).text())
                .isEqualTo("fits now");
    }

    // ---- refill ----

    @Test
    @DisplayName("the first refill asks the three seeds in order, in the account's language, and the second asks nothing new")
    void refillSeedsOnce() {
        List<SecondBrainQuestionDTO> first = service.refill(owner);

        assertThat(first).extracting(SecondBrainQuestionDTO::kind).containsExactly(
                SecondBrainQuestionKind.PROFILE_OBJECTIVES,
                SecondBrainQuestionKind.PROFILE_LIKES,
                SecondBrainQuestionKind.PROFILE_ABOUT);
        assertThat(first).extracting(SecondBrainQuestionDTO::text).containsExactly(
                "What are the main things you want out of this year?",
                "What do you enjoy doing when the day is yours?",
                "Tell me about your week: who you live with and what you do for work.");
        assertThat(first).allSatisfy(question -> {
            assertThat(question.status()).isEqualTo(SecondBrainQuestionStatus.OPEN);
            assertThat(question.answer()).isNull();
            assertThat(question.answeredAt()).isNull();
        });

        setClock(NOW.plusSeconds(3600));
        assertThat(service.refill(owner)).isEqualTo(first);
        assertThat(service.overview(owner).questions()).isEqualTo(first);

        User brazilian = newUser("brain-pt", "UTC", "pt-BR");
        assertThat(service.refill(brazilian)).extracting(SecondBrainQuestionDTO::text).containsExactly(
                "Quais são as principais coisas que você quer deste ano?",
                "O que você gosta de fazer quando o dia é seu?",
                "Me conta da sua semana: com quem você mora e com o que trabalha.");
    }

    @Test
    @DisplayName("a seed is never asked twice, answered or skipped, and never for a kind the profile already has")
    void seedsAreNotRepeated() {
        service.createFact(owner, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "Cooking"));

        List<SecondBrainQuestionDTO> asked = service.refill(owner);
        assertThat(asked).extracting(SecondBrainQuestionDTO::kind).containsExactly(
                SecondBrainQuestionKind.PROFILE_OBJECTIVES, SecondBrainQuestionKind.PROFILE_ABOUT);

        service.skip(owner, asked.get(0).id());
        SecondBrainFactDTO learned = service.answer(owner, asked.get(1).id(),
                new SecondBrainAnswerRequestDTO("I live alone and write software")).learned().get(0);
        // Even with the fact gone again, the question was asked once and that is enough.
        service.deleteFact(owner, learned.id());

        assertThat(service.refill(owner)).isEmpty();
        assertThat(rows("second_brain_questions", owner)).isEqualTo(2);
    }

    @Test
    @DisplayName("a refill never leaves more than five questions open")
    void refillStopsAtFiveOpen() {
        for (int i = 0; i < 4; i++) {
            question(owner, SecondBrainQuestionKind.FOLLOW_UP, "Earlier question " + i, NOW.minusSeconds(86_400 - i));
        }
        // Today's routine would earn a check-in too, if there were room for it.
        routine(owner, habit(owner, "Run", category(owner, "Health")), Set.of(WeekDay.Wednesday));

        List<SecondBrainQuestionDTO> open = service.refill(owner);

        assertThat(open).hasSize(SecondBrainQuestion.MAX_OPEN);
        assertThat(open.get(4).kind()).as("one seed fits; the other two and the check-in wait")
                .isEqualTo(SecondBrainQuestionKind.PROFILE_OBJECTIVES);
        assertThat(service.refill(owner)).hasSize(SecondBrainQuestion.MAX_OPEN);

        // Closing one makes room for exactly the next in line. On a later visit, so on a later
        // clock: with the clock frozen the new seed would share its created_at with the first
        // one, and a tie is broken by id, which is random. That made this fail every other run.
        setClock(NOW.plusSeconds(1));
        service.skip(owner, open.get(0).id());
        assertThat(service.refill(owner)).extracting(SecondBrainQuestionDTO::kind)
                .hasSize(SecondBrainQuestion.MAX_OPEN)
                .endsWith(SecondBrainQuestionKind.PROFILE_OBJECTIVES, SecondBrainQuestionKind.PROFILE_LIKES);
    }

    @Test
    @DisplayName("the check-in is asked once per day of the account's own calendar, on a day a routine runs")
    void checkInOncePerOwnDay() {
        // Sao Paulo is three hours behind UTC in November. Three, so no server zone a test
        // runner is likely to sit in can make the day boundary land right by accident.
        User traveller = newUser("brain-sp", "America/Sao_Paulo", "pt");
        routine(traveller, habit(traveller, "Correr", category(traveller, "Saúde")), Set.of(WeekDay.Tuesday));
        closeSeeds(traveller);

        // 01:00 UTC on Wednesday is still Tuesday 22:00 there, and Tuesday is the routine's day.
        setClock(Instant.parse("2026-11-11T01:00:00Z"));
        List<SecondBrainQuestionDTO> tuesdayNight = service.refill(traveller);
        assertThat(tuesdayNight).extracting(SecondBrainQuestionDTO::kind).containsExactly(SecondBrainQuestionKind.CHECK_IN);
        assertThat(tuesdayNight.get(0).text())
                .isEqualTo("Como está o seu dia? Me descreve e eu marco ou pulo os itens da sua rotina.");

        // Later the same local day: answered or not, there is no second one.
        setClock(Instant.parse("2026-11-11T02:30:00Z"));
        service.skip(traveller, tuesdayNight.get(0).id());
        assertThat(service.refill(traveller)).isEmpty();

        // 04:00 UTC is Wednesday 01:00 there, and the routine does not run on Wednesday.
        setClock(Instant.parse("2026-11-11T04:00:00Z"));
        assertThat(service.refill(traveller)).isEmpty();

        // The next Tuesday asks again.
        setClock(Instant.parse("2026-11-17T15:00:00Z"));
        assertThat(service.refill(traveller)).extracting(SecondBrainQuestionDTO::kind)
                .containsExactly(SecondBrainQuestionKind.CHECK_IN);
    }

    @Test
    @DisplayName("no check-in with ROUTINES off, or on a day no routine runs")
    void checkInNeedsARoutineTodayAndTheSwitch() {
        closeSeeds(owner);
        routine(owner, habit(owner, "Run", category(owner, "Health")), Set.of(WeekDay.Monday));
        assertThat(service.refill(owner)).as("Wednesday, and the routine is Monday's").isEmpty();

        User switchedOff = newUser("brain-off", "UTC", "en");
        closeSeeds(switchedOff);
        routine(switchedOff, habit(switchedOff, "Run", category(switchedOff, "Health")), Set.of(WeekDay.Wednesday));
        service.setSource(switchedOff, SecondBrainSource.ROUTINES, false);
        assertThat(service.refill(switchedOff)).isEmpty();

        service.setSource(switchedOff, SecondBrainSource.ROUTINES, true);
        assertThat(service.refill(switchedOff)).extracting(SecondBrainQuestionDTO::kind)
                .containsExactly(SecondBrainQuestionKind.CHECK_IN);
    }

    @Test
    @DisplayName("follow-ups fill the room that is left, skip repeats, and pause for six hours")
    void followUpsRespectRoomRepeatsAndThePause() {
        service.refill(owner);
        assertThat(store.seed(owner).followUpRoom()).as("three seeds are open").isEqualTo(2);

        setClock(NOW.plusSeconds(1));
        store.addFollowUps(owner.getId(), List.of(
                "What do you enjoy doing when the day is yours?",
                "  What is the half marathon for?  ",
                "",
                "x".repeat(400) + "?",
                "A third that does not fit?"));

        List<SecondBrainQuestionDTO> open = service.overview(owner).questions();
        assertThat(open).hasSize(SecondBrainQuestion.MAX_OPEN);
        assertThat(open.get(3).kind()).isEqualTo(SecondBrainQuestionKind.FOLLOW_UP);
        assertThat(open.get(3).text()).isEqualTo("What is the half marathon for?");
        assertThat(open.get(4).text()).hasSize(SecondBrainQuestion.TEXT_MAX);

        // Room again, and still nothing until six hours after the last follow-up was written.
        service.skip(owner, open.get(3).id());
        service.skip(owner, open.get(4).id());
        setClock(NOW.plus(6, ChronoUnit.HOURS));
        assertThat(store.seed(owner).followUpRoom()).isZero();
        store.addFollowUps(owner.getId(), List.of("Too soon?"));
        assertThat(service.overview(owner).questions()).hasSize(3);

        setClock(NOW.plus(6, ChronoUnit.HOURS).plusSeconds(2));
        assertThat(store.seed(owner).followUpRoom()).isEqualTo(2);
        store.addFollowUps(owner.getId(), List.of("Late enough?"));
        assertThat(service.overview(owner).questions()).extracting(SecondBrainQuestionDTO::text).endsWith("Late enough?");
    }

    // ---- answer and skip ----

    @Test
    @DisplayName("without a model, the answer to a profile question becomes one fact of its kind")
    void profileAnswerBecomesAFact() {
        SecondBrainQuestionDTO objectives = service.refill(owner).get(0);
        setClock(NOW.plusSeconds(90));

        SecondBrainAnswerResultDTO result = service.answer(owner, objectives.id(),
                new SecondBrainAnswerRequestDTO("  Run a half marathon by June  "));

        assertThat(result.question().id()).isEqualTo(objectives.id());
        assertThat(result.question().status()).isEqualTo(SecondBrainQuestionStatus.ANSWERED);
        assertThat(result.question().answer()).isEqualTo("Run a half marathon by June");
        assertThat(result.question().answeredAt()).isEqualTo(NOW.plusSeconds(90));
        assertThat(result.learned()).hasSize(1);
        SecondBrainFactDTO fact = result.learned().get(0);
        assertThat(fact.kind()).isEqualTo(SecondBrainFactKind.OBJECTIVE);
        assertThat(fact.origin()).isEqualTo(SecondBrainFactOrigin.ANSWER);
        assertThat(fact.text()).isEqualTo("Run a half marathon by June");

        SecondBrainOverviewDTO page = service.overview(owner);
        assertThat(page.facts()).containsExactly(fact);
        assertThat(page.questions()).extracting(SecondBrainQuestionDTO::kind).containsExactly(
                SecondBrainQuestionKind.PROFILE_LIKES, SecondBrainQuestionKind.PROFILE_ABOUT);
    }

    @Test
    @DisplayName("a long answer is kept whole on the question and cut to the fact limit on the fact")
    void longProfileAnswerIsCutOnTheFactOnly() {
        SecondBrainQuestionDTO likes = service.refill(owner).get(1);
        String answer = "I like ".concat("walking ".repeat(80)).strip();

        SecondBrainAnswerResultDTO result = service.answer(owner, likes.id(), new SecondBrainAnswerRequestDTO(answer));

        assertThat(result.question().answer()).isEqualTo(answer);
        assertThat(result.learned().get(0).kind()).isEqualTo(SecondBrainFactKind.LIKE);
        assertThat(result.learned().get(0).text()).hasSizeLessThanOrEqualTo(SecondBrainFact.TEXT_MAX)
                .startsWith("I like walking walking");
    }

    @Test
    @DisplayName("without a model, a check-in answer is stored and teaches nothing")
    void checkInAnswerIsStoredOnly() {
        UUID checkIn = question(owner, SecondBrainQuestionKind.CHECK_IN, "How is your day going?", NOW);

        SecondBrainAnswerResultDTO result = service.answer(owner, checkIn, new SecondBrainAnswerRequestDTO("Ran, skipped reading"));

        assertThat(result.question().status()).isEqualTo(SecondBrainQuestionStatus.ANSWERED);
        assertThat(result.question().answer()).isEqualTo("Ran, skipped reading");
        assertThat(result.learned()).isEmpty();
        assertThat(service.overview(owner).facts()).isEmpty();
    }

    @Test
    @DisplayName("a full profile learns nothing more, and the question still closes")
    void learningRespectsTheFactLimit() {
        for (int i = 0; i < SecondBrainFact.MAX_PER_USER; i++) {
            service.createFact(owner, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "fact " + i));
        }
        UUID about = question(owner, SecondBrainQuestionKind.PROFILE_ABOUT, "Tell me about your week", NOW);

        SecondBrainAnswerResultDTO result = service.answer(owner, about, new SecondBrainAnswerRequestDTO("I live alone"));

        assertThat(result.learned()).isEmpty();
        assertThat(result.question().status()).isEqualTo(SecondBrainQuestionStatus.ANSWERED);
        assertThat(service.overview(owner).facts()).hasSize(SecondBrainFact.MAX_PER_USER);
    }

    @Test
    @DisplayName("answering the same way twice does not write the fact twice")
    void aLearnedFactIsNotDuplicated() {
        service.createFact(owner, new SecondBrainFactRequestDTO(SecondBrainFactKind.ABOUT, "i live alone"));
        UUID about = question(owner, SecondBrainQuestionKind.PROFILE_ABOUT, "Tell me about your week", NOW);

        assertThat(service.answer(owner, about, new SecondBrainAnswerRequestDTO("I live alone")).learned()).isEmpty();
        assertThat(service.overview(owner).facts()).hasSize(1);
    }

    @Test
    @DisplayName("a closed question refuses a second answer and a skip with 409, and keeps the first answer")
    void closedQuestionsStayClosed() {
        List<SecondBrainQuestionDTO> seeds = service.refill(owner);
        UUID answered = seeds.get(0).id();
        UUID skipped = seeds.get(1).id();
        service.answer(owner, answered, new SecondBrainAnswerRequestDTO("the first answer"));
        service.skip(owner, skipped);

        for (UUID closed : List.of(answered, skipped)) {
            assertErrorKey(() -> service.answer(owner, closed, new SecondBrainAnswerRequestDTO("again")),
                    ErrorKey.SECOND_BRAIN_QUESTION_CLOSED);
            assertErrorKey(() -> service.skip(owner, closed), ErrorKey.SECOND_BRAIN_QUESTION_CLOSED);
        }

        entityManager.flush();
        entityManager.clear();
        assertThat(questionRepository.findById(answered).orElseThrow().getAnswer()).isEqualTo("the first answer");
        assertThat(service.overview(owner).facts()).as("one answer, one fact").hasSize(1);
    }

    @Test
    @DisplayName("skipping closes the question without an answer or a date")
    void skipClosesWithoutAnAnswer() {
        SecondBrainQuestionDTO likes = service.refill(owner).get(1);

        SecondBrainQuestionDTO skipped = service.skip(owner, likes.id());

        assertThat(skipped.status()).isEqualTo(SecondBrainQuestionStatus.SKIPPED);
        assertThat(skipped.answer()).isNull();
        assertThat(skipped.answeredAt()).isNull();
        assertThat(service.overview(owner).questions()).extracting(SecondBrainQuestionDTO::id).doesNotContain(likes.id());
        assertThat(service.overview(owner).facts()).isEmpty();
    }

    @Test
    @DisplayName("someone else's question is a 404 on answer and skip, and stays open")
    void questionsAreOwned() {
        UUID theirs = service.refill(stranger).get(0).id();

        assertErrorKey(() -> service.answer(owner, theirs, new SecondBrainAnswerRequestDTO("mine now")),
                ErrorKey.SECOND_BRAIN_QUESTION_NOT_FOUND);
        assertErrorKey(() -> service.skip(owner, theirs), ErrorKey.SECOND_BRAIN_QUESTION_NOT_FOUND);
        assertErrorKey(() -> service.skip(owner, UUID.randomUUID()), ErrorKey.SECOND_BRAIN_QUESTION_NOT_FOUND);

        assertThat(service.overview(stranger).questions()).extracting(SecondBrainQuestionDTO::id).contains(theirs);
        assertThat(service.overview(owner).facts()).isEmpty();
    }

    // ---- insight ----

    @Test
    @DisplayName("without a model the overview cannot be refreshed, and nothing is stored")
    void refreshWithoutAModelIsUnavailable() {
        assertErrorKey(() -> service.refreshInsight(owner), ErrorKey.AI_UNAVAILABLE);
        assertThat(rows("second_brain_insights", owner)).isZero();
    }

    @Test
    @DisplayName("an overview is one row per account, replaced on refresh, and stale from the account's next day on")
    void insightIsUpsertedAndGoesStaleOnTheNextDay() {
        SecondBrainInsightDTO first = store.saveInsight(owner.getId(), SecondBrainVerdict.ON_COURSE, "Your mornings hold.");
        assertThat(first).isEqualTo(new SecondBrainInsightDTO("Your mornings hold.", SecondBrainVerdict.ON_COURSE, NOW, false));
        assertThat(service.overview(owner).insight()).isEqualTo(first);
        assertThat(service.overview(stranger).insight()).isEqualTo(SecondBrainInsightDTO.none());

        // The owner lives on UTC. One second before midnight is still the day it was written.
        setClock(Instant.parse("2026-11-11T23:59:59Z"));
        assertThat(service.overview(owner).insight().stale()).as("later the same day").isFalse();
        setClock(Instant.parse("2026-11-12T00:00:00Z"));
        assertThat(service.overview(owner).insight())
                .isEqualTo(new SecondBrainInsightDTO("Your mornings hold.", SecondBrainVerdict.ON_COURSE, NOW, true));

        SecondBrainInsightDTO second = store.saveInsight(owner.getId(), SecondBrainVerdict.DRIFTING, "Your evenings slipped.");
        assertThat(service.overview(owner).insight()).isEqualTo(second);
        assertThat(second.stale()).isFalse();
        assertThat(rows("second_brain_insights", owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("the day an overview goes stale is the account's own, not the server's")
    void insightStalenessFollowsTheAccountsDay() {
        // Sao Paulo is three hours behind UTC in November. Three, so no server zone a test
        // runner is likely to sit in can make the day boundary land right by accident.
        User traveller = newUser("brain-sp-insight", "America/Sao_Paulo", "pt");
        // 01:00 UTC on Wednesday is Tuesday 22:00 there.
        setClock(Instant.parse("2026-11-11T01:00:00Z"));
        store.saveInsight(traveller.getId(), SecondBrainVerdict.TOO_EARLY, "Cedo demais para dizer.");

        // 02:59 UTC is still Tuesday there.
        setClock(Instant.parse("2026-11-11T02:59:00Z"));
        assertThat(service.overview(traveller).insight().stale()).isFalse();
        // 03:00 UTC is Wednesday 00:00 there: a new day for them, the same date for a UTC server.
        setClock(Instant.parse("2026-11-11T03:00:00Z"));
        assertThat(service.overview(traveller).insight().stale()).isTrue();
    }

    // ---- helpers ----

    private void assertErrorKey(ThrowingCallable call, ErrorKey key) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(key);
    }

    private void setClock(Instant instant) {
        Object target = AopTestUtils.getTargetObject(store);
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(instant, ZoneOffset.UTC));
    }

    private int rows(String table, User user) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user.getId());
    }

    /** Asks and closes the three seeds, so a refill under test has only the check-in left to decide. */
    private void closeSeeds(User user) {
        service.refill(user).stream()
                .filter(question -> question.kind().isProfile())
                .forEach(question -> service.skip(user, question.id()));
    }

    /** A question written straight to the table, for the kinds a refill without a model never asks. */
    private UUID question(User user, SecondBrainQuestionKind kind, String text, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO second_brain_questions (id, user_id, kind, text, status, created_at) "
                + "VALUES (?, ?, ?, ?, 'OPEN', ?)", id, user.getId(), kind.name(), text,
                java.sql.Timestamp.from(createdAt));
        return id;
    }

    private void calendarTask(User user, String title, LocalDate day, boolean completed) {
        java.sql.Timestamp now = java.sql.Timestamp.from(NOW);
        jdbc.update("INSERT INTO calendar_tasks (id, user_id, title, task_date, advance_minutes, completed_at, "
                + "xp_awarded, created_at, updated_at) VALUES (?, ?, ?, ?, 0, ?, 0, ?, ?)",
                UUID.randomUUID(), user.getId(), title, day, completed ? now : null, now, now);
    }

    private void quickNote(User user, String text, boolean archived) {
        java.sql.Timestamp now = java.sql.Timestamp.from(NOW);
        jdbc.update("INSERT INTO quick_notes (id, user_id, text, color, x, y, z, archived_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'YELLOW', 0, 0, 0, ?, ?, ?)",
                UUID.randomUUID(), user.getId(), text, archived ? now : null, now, now);
    }

    private Category category(User user, String name) {
        Category category = new Category();
        category.setName(name);
        category.setIconId("icon");
        category.setUser(user);
        category.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return categoryRepository.saveAndFlush(category);
    }

    private Habit habit(User user, String name, Category... categories) {
        Habit habit = new Habit();
        habit.setName(name);
        habit.setIconId("icon");
        habit.setImportance(3);
        habit.setDificulty(3);
        habit.setDescription("d");
        habit.setMotivationalPhrase("m");
        habit.setCategories(new ArrayList<>(List.of(categories)));
        habit.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        habit.setUser(user);
        return habitRepository.saveAndFlush(habit);
    }

    private Task task(User user, String name, Category... categories) {
        Task task = new Task();
        task.setName(name);
        task.setIconId("icon");
        task.setCategories(new ArrayList<>(List.of(categories)));
        task.setUser(user);
        return taskRepository.saveAndFlush(task);
    }

    private void goal(User user, String name, LocalDate endDate) {
        Goal goal = new Goal();
        goal.setName(name);
        goal.setIconId("icon");
        goal.setTargetValue(10d);
        goal.setCurrentValue(4d);
        goal.setUnit("books");
        goal.setComplete(false);
        goal.setStartDate(TODAY.minusDays(30));
        goal.setEndDate(endDate);
        goal.setXpReward(0);
        goal.setUser(user);
        goal.setStatus(GoalStatus.IN_PROGRESS);
        goal.setTerm(GoalTerm.SHORT_TERM);
        goal.setCategories(new ArrayList<>());
        goalRepository.saveAndFlush(goal);
    }

    /** One routine with one section holding the habit, scheduled on the given days. */
    private DiaryRoutine routine(User user, Habit habit, Set<WeekDay> days) {
        Schedule schedule = new Schedule();
        schedule.setDays(days);
        schedule = scheduleRepository.saveAndFlush(schedule);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setName("Morning");
        routine.setIconId("icon");
        routine.setUser(user);
        routine.setSchedule(schedule);
        routine.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

        RoutineSection section = new RoutineSection();
        section.setName("Warm-up");
        section.setIconId("icon");
        section.setStartTime(LocalTime.of(6, 0));
        section.setEndTime(LocalTime.of(7, 0));
        section.setOrderIndex(0);
        section.setFavorite(false);
        section.setRoutine(routine);

        HabitGroup group = new HabitGroup();
        group.setHabit(habit);
        group.setRoutineSection(section);
        group.setStartTime(LocalTime.of(6, 0));
        group.setEndTime(LocalTime.of(6, 30));
        group.setHabitGroupChecks(new ArrayList<>());

        section.setHabitGroups(new ArrayList<>(List.of(group)));
        section.setTaskGroups(new ArrayList<>());
        routine.setRoutineSections(new ArrayList<>(List.of(section)));
        return diaryRoutineRepository.saveAndFlush(routine);
    }

    private RoutineSnapshot snapshot(User user, DiaryRoutine routine, LocalDate date) {
        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setRoutine(routine);
        snapshot.setUser(user);
        snapshot.setSnapshotDate(date);
        snapshot.setRoutineName(routine.getName());
        snapshot.setRoutineIconId(routine.getIconId());
        snapshot.setStructureJson("{\"sections\":[]}");
        snapshot.setCompleted(false);
        snapshot.setChecks(new ArrayList<>());
        return snapshot;
    }

    private void check(RoutineSnapshot snapshot, SnapshotItemType type, UUID itemId, String name,
                       Consumer<SnapshotCheck> tweak) {
        SnapshotCheck check = new SnapshotCheck();
        check.setSnapshot(snapshot);
        check.setItemType(type);
        check.setItemName(name);
        check.setItemIconId("icon");
        check.setSectionName("Warm-up");
        check.setOriginalItemId(itemId);
        check.setDifficulty(3);
        check.setImportance(3);
        tweak.accept(check);
        snapshot.getChecks().add(check);
    }

    private User newUser(String tag, String timezone, String language) {
        User user = new User();
        user.setName(tag);
        user.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user.setGoogleAccount(false);
        user.setTimezone(timezone);
        user.setLanguageInUse(language);
        user.setCompletedDays(new HashSet<>());
        user.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        user.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(user);
    }
}
