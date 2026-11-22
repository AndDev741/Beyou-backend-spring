package beyou.beyouapp.backend.integration.secondbrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.habit.dto.EditHabitDTO;
import beyou.beyouapp.backend.domain.routine.RoutineType;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineItemRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainFactOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNote;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNoteOrigin;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainNotes;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionKind;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainQuestionStatus;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainService;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSignals;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSource;
import beyou.beyouapp.backend.domain.secondbrain.SecondBrainSubjectType;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainAnswerResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainFactRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainMemoryGroupDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainMemorySubjectDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainNoteDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainNoteRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOldNoteRequestDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainOldNoteResultDTO;
import beyou.beyouapp.backend.domain.secondbrain.dto.SecondBrainQuestionDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

/**
 * Second Brain step 2, the memory, against a real Postgres and through the services the REST
 * routes and the assistant's tools call: how notes are grouped and capped, who can touch them,
 * when a new thing gets its question, what happens to notes when their thing is deleted, and
 * the old hidden note's review.
 *
 * <p>Deliberately NOT {@code @Transactional}. A NEW_THING question is written after the create
 * commits, and a test transaction never commits, so it would hide exactly the rule under test.
 * Every account is deleted afterwards through the account deletion path, which also proves the
 * notes go with it.
 *
 * <p>No model is configured in the test profile. What a model adds is in
 * {@link SecondBrainModelCallIT}.
 */
class SecondBrainMemoryIT extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-20T09:00:00Z");

    @Autowired private SecondBrainService service;
    @Autowired private SecondBrainNotes notes;
    @Autowired private SecondBrainSignals signals;
    @Autowired private HabitService habitService;
    @Autowired private GoalService goalService;
    @Autowired private CategoryService categoryService;
    @Autowired private DiaryRoutineService routineService;
    @Autowired private HabitRepository habitRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> accounts = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        setNotesClock(Clock.systemDefaultZone());
        for (UUID id : accounts) {
            userRepository.findById(id).ifPresent(userService::deleteUser);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM second_brain_notes WHERE user_id = ?",
                    Integer.class, id)).as("the notes go with the account").isZero();
        }
        accounts.clear();
    }

    // ---- NEW_THING questions ----

    @Test
    @DisplayName("a new habit, goal or routine is asked about once, only after the first visit, and never past five open")
    void newThingsAreAskedAboutAfterTheFirstVisit() {
        User owner = newUser("en");
        UUID health = category(owner, "Health");
        UUID gym = habit(owner, "Gym", health);
        assertThat(questions(owner)).as("the page was never opened, so nothing is asked").isEmpty();

        service.refill(owner);
        assertThat(questions(owner)).hasSize(3);

        UUID run = habit(owner, "Run", health);
        UUID books = goal(owner, "Read twelve books", health);
        assertThat(newThing(owner)).extracting(row -> row.get("subject_type"), row -> row.get("subject_id"), row -> row.get("text"))
                .containsExactly(
                        tuple("HABIT", run,
                                "You started the habit \"Run\". What led you to it, and what usually gets in the way?"),
                        tuple("GOAL", books,
                                "You set the goal \"Read twelve books\". What led you to it, and what could get in the way?"));

        routine(owner, "Mornings", gym);
        assertThat(newThing(owner)).as("five are open, so the routine waits for room").hasSize(2);

        SecondBrainQuestionDTO seed = service.refill(owner).get(0);
        service.skip(owner, seed.id());
        UUID evenings = routine(owner, "Evenings", gym);
        assertThat(newThing(owner)).extracting(row -> row.get("subject_id")).containsExactly(run, books, evenings);

        // Once per thing, whatever became of the first question.
        SecondBrainQuestionDTO aboutRun = openNewThing(owner, run);
        service.skip(owner, aboutRun.id());
        assertThat(notes.askAboutNewThing(owner.getId(), "en", SecondBrainSubjectType.HABIT, run, "Run")).isFalse();
        assertThat(newThing(owner)).hasSize(3);
    }

    @Test
    @DisplayName("the question is stored in the account's language, and the routine's waits for its transaction to commit")
    void newThingQuestionsSpeakTheAccountsLanguage() {
        User owner = newUser("pt");
        UUID health = category(owner, "Saúde");
        UUID walk = habit(owner, "Caminhar", health);
        service.refill(owner);

        UUID mornings = routine(owner, "Manhãs", walk);

        assertThat(newThing(owner)).extracting(row -> row.get("subject_id"), row -> row.get("text")).containsExactly(
                tuple(mornings, "Você criou a rotina \"Manhãs\". O que te levou a ela, e o que costuma atrapalhar?"));
    }

    @Test
    @DisplayName("without a model, the answer about a new thing becomes one note on it, as written and cut to 500")
    void aNewThingAnswerWithoutAModelIsOneNote() {
        User owner = newUser("en");
        UUID health = category(owner, "Health");
        service.refill(owner);
        UUID run = habit(owner, "Run", health);
        UUID marathon = goal(owner, "Marathon", health);

        SecondBrainAnswerResultDTO answered = service.answer(owner, openNewThing(owner, run).id(),
                new SecondBrainAnswerRequestDTO("  More energy for work.\nRain is what stops me.  "));
        SecondBrainAnswerResultDTO longOne = service.answer(owner, openNewThing(owner, marathon).id(),
                new SecondBrainAnswerRequestDTO("z".repeat(600)));

        assertThat(answered.learned()).isEmpty();
        assertThat(answered.notes()).extracting(SecondBrainNoteDTO::text, SecondBrainNoteDTO::origin,
                SecondBrainNoteDTO::subjectName).containsExactly(
                        tuple("More energy for work.\nRain is what stops me.", SecondBrainNoteOrigin.ANSWER, "Run"));
        assertThat(longOne.notes().get(0).text()).hasSize(SecondBrainNote.TEXT_MAX);

        List<SecondBrainMemoryGroupDTO> memory = service.overview(owner).memory();
        assertThat(memory).extracting(SecondBrainMemoryGroupDTO::categoryName).containsExactly("Health");
        assertThat(memory.get(0).subjects()).extracting(SecondBrainMemorySubjectDTO::type, SecondBrainMemorySubjectDTO::name)
                .containsExactly(tuple(SecondBrainSubjectType.GOAL, "Marathon"), tuple(SecondBrainSubjectType.HABIT, "Run"));
    }

    // ---- the memory block ----

    /**
     * "Mind" sorts before "health" by code point and after it by letters, so the order below
     * only comes out right when names compare ignoring case, for groups and for a thing's first
     * category alike.
     */
    @Test
    @DisplayName("notes are grouped under a thing's first category by name, the category's own first, routines and loose ones last")
    void memoryIsGroupedAndOrderedOnTheServer() {
        User owner = newUser("en");
        UUID mind = category(owner, "Mind");
        UUID health = category(owner, "health");
        UUID run = habit(owner, "Run", mind, health);
        UUID swim = habit(owner, "swim", mind);
        UUID club = goal(owner, "Book club", mind);
        UUID mornings = routine(owner, "Mornings", swim);
        UUID evenings = routine(owner, "Evenings", swim);

        setNotesClock(Clock.fixed(T0, ZoneOffset.UTC));
        add(owner, SecondBrainSubjectType.HABIT, run, "Wants more energy", SecondBrainNoteOrigin.ANSWER);
        setNotesClock(Clock.fixed(T0.plusSeconds(60), ZoneOffset.UTC));
        add(owner, SecondBrainSubjectType.HABIT, run, "Rain stops it", SecondBrainNoteOrigin.ASSISTANT);
        add(owner, SecondBrainSubjectType.HABIT, swim, "Pool opens at six", SecondBrainNoteOrigin.ANSWER);
        add(owner, SecondBrainSubjectType.GOAL, club, "Started with a friend", SecondBrainNoteOrigin.ANSWER);
        add(owner, SecondBrainSubjectType.CATEGORY, mind, "Reading more this year", SecondBrainNoteOrigin.ASSISTANT);
        add(owner, SecondBrainSubjectType.ROUTINE, mornings, "Before the kids wake", SecondBrainNoteOrigin.ANSWER);
        add(owner, SecondBrainSubjectType.ROUTINE, evenings, "Too tired after work", SecondBrainNoteOrigin.ANSWER);
        routineService.deleteDiaryRoutine(evenings, owner.getId());

        // A rename shows at once: the name on the page is the live one.
        habitService.editHabit(new EditHabitDTO(run, "Run 5k", "", "", "lucide:heart", 3, 3,
                List.of(mind, health)), owner.getId());

        List<SecondBrainMemoryGroupDTO> memory = service.overview(owner).memory();

        assertThat(memory).extracting(SecondBrainMemoryGroupDTO::categoryId, SecondBrainMemoryGroupDTO::categoryName)
                .containsExactly(tuple(health, "health"), tuple(mind, "Mind"), tuple(null, null));
        assertThat(memory.get(0).subjects()).singleElement().satisfies(subject -> {
            assertThat(subject.type()).isEqualTo(SecondBrainSubjectType.HABIT);
            assertThat(subject.id()).isEqualTo(run);
            assertThat(subject.name()).isEqualTo("Run 5k");
            assertThat(subject.iconId()).isEqualTo("lucide:heart");
            assertThat(subject.notes()).as("oldest first").extracting(SecondBrainNoteDTO::text)
                    .containsExactly("Wants more energy", "Rain stops it");
            assertThat(subject.notes()).extracting(SecondBrainNoteDTO::subjectName).containsOnly("Run");
        });
        assertThat(memory.get(1).subjects()).extracting(SecondBrainMemorySubjectDTO::type, SecondBrainMemorySubjectDTO::name)
                .as("the category's own notes head its group, then names in any letter case")
                .containsExactly(
                        tuple(SecondBrainSubjectType.CATEGORY, "Mind"),
                        tuple(SecondBrainSubjectType.GOAL, "Book club"),
                        tuple(SecondBrainSubjectType.HABIT, "swim"));
        assertThat(memory.get(2).subjects()).extracting(SecondBrainMemorySubjectDTO::type,
                SecondBrainMemorySubjectDTO::id, SecondBrainMemorySubjectDTO::name, SecondBrainMemorySubjectDTO::formerType)
                .containsExactly(
                        tuple(SecondBrainSubjectType.LOOSE, null, "Evenings", SecondBrainSubjectType.ROUTINE),
                        tuple(SecondBrainSubjectType.ROUTINE, mornings, "Mornings", null));
    }

    @Test
    @DisplayName("a thing keeps its latest thirty notes: a thirty-first removes the oldest of that thing only")
    void aThingKeepsItsLatestThirtyNotes() {
        User owner = newUser("en");
        UUID health = category(owner, "Health");
        UUID run = habit(owner, "Run", health);
        add(owner, SecondBrainSubjectType.CATEGORY, health, "the category's own", SecondBrainNoteOrigin.ANSWER);

        for (int i = 0; i <= SecondBrainNote.MAX_PER_SUBJECT; i++) {
            setNotesClock(Clock.fixed(T0.plusSeconds(i), ZoneOffset.UTC));
            add(owner, SecondBrainSubjectType.HABIT, run, "note " + i, SecondBrainNoteOrigin.ANSWER);
        }

        List<SecondBrainMemorySubjectDTO> subjects = service.overview(owner).memory().get(0).subjects();
        assertThat(subjects.get(0).notes()).extracting(SecondBrainNoteDTO::text).containsExactly("the category's own");
        List<String> runNotes = subjects.get(1).notes().stream().map(SecondBrainNoteDTO::text).toList();
        assertThat(runNotes).hasSize(SecondBrainNote.MAX_PER_SUBJECT);
        assertThat(runNotes).first().isEqualTo("note 1");
        assertThat(runNotes).last().isEqualTo("note 30");
    }

    @Test
    @DisplayName("a note is only reachable by its owner, and an edit keeps who wrote it")
    void notesBelongToTheirOwner() {
        User owner = newUser("en");
        User stranger = newUser("en");
        UUID health = category(owner, "Health");
        UUID run = habit(owner, "Run", health);
        SecondBrainNoteDTO note = add(owner, SecondBrainSubjectType.HABIT, run, "Rain stops it", SecondBrainNoteOrigin.ASSISTANT);

        assertErrorKey(() -> service.editNote(stranger, note.id(), new SecondBrainNoteRequestDTO("mine now")),
                ErrorKey.SECOND_BRAIN_NOTE_NOT_FOUND);
        assertErrorKey(() -> service.deleteNote(stranger, note.id()), ErrorKey.SECOND_BRAIN_NOTE_NOT_FOUND);
        assertErrorKey(() -> service.editNote(owner, UUID.randomUUID(), new SecondBrainNoteRequestDTO("x")),
                ErrorKey.SECOND_BRAIN_NOTE_NOT_FOUND);
        assertThat(notes.add(stranger.getId(), SecondBrainSubjectType.HABIT, run, "planted", SecondBrainNoteOrigin.ANSWER))
                .as("no note on someone else's thing").isEmpty();

        SecondBrainNoteDTO edited = service.editNote(owner, note.id(), new SecondBrainNoteRequestDTO("  Snow stops it  "));
        assertThat(edited.text()).isEqualTo("Snow stops it");
        assertThat(edited.origin()).as("a corrected note was still the assistant's").isEqualTo(SecondBrainNoteOrigin.ASSISTANT);
        assertThat(edited.updatedAt()).isAfterOrEqualTo(note.updatedAt());

        service.deleteNote(owner, note.id());
        assertThat(service.overview(owner).memory()).isEmpty();
    }

    // ---- when a thing is deleted ----

    @Test
    @DisplayName("a deleted habit hands its notes to its first category, a routine's turn loose, and the open question goes")
    void deletingAThingMovesItsNotes() {
        User owner = newUser("en");
        UUID mind = category(owner, "Mind");
        UUID health = category(owner, "health");
        UUID keep = habit(owner, "Keep", mind);
        service.refill(owner);
        UUID run = habit(owner, "Run", mind, health);
        UUID walk = habit(owner, "Walk", health);
        UUID solo = goal(owner, "Solo goal");
        UUID mornings = routine(owner, "Mornings", keep);
        add(owner, SecondBrainSubjectType.CATEGORY, health, "Health matters more now", SecondBrainNoteOrigin.ANSWER);
        add(owner, SecondBrainSubjectType.HABIT, run, "Wants more energy", SecondBrainNoteOrigin.ANSWER);
        add(owner, SecondBrainSubjectType.HABIT, run, "Rain stops it", SecondBrainNoteOrigin.ASSISTANT);
        add(owner, SecondBrainSubjectType.GOAL, solo, "A goal with no category", SecondBrainNoteOrigin.ANSWER);
        add(owner, SecondBrainSubjectType.ROUTINE, mornings, "Before the kids wake", SecondBrainNoteOrigin.ANSWER);
        // Run's question is still open; Walk's was answered, which is something the person said.
        service.answer(owner, openNewThing(owner, walk).id(), new SecondBrainAnswerRequestDTO("A dog to walk"));

        habitService.deleteHabit(run, owner.getId());
        habitService.deleteHabit(walk, owner.getId());
        goalService.deleteGoal(solo, owner.getId());
        routineService.deleteDiaryRoutine(mornings, owner.getId());

        assertThat(noteRows(owner)).extracting(row -> row.get("text"), row -> row.get("subject_type"),
                row -> row.get("subject_id"), row -> row.get("former_type"), row -> row.get("subject_name"))
                .containsExactlyInAnyOrder(
                        tuple("Health matters more now", "CATEGORY", health, null, "health"),
                        tuple("Wants more energy", "CATEGORY", health, "HABIT", "Run"),
                        tuple("Rain stops it", "CATEGORY", health, "HABIT", "Run"),
                        tuple("A dog to walk", "CATEGORY", health, "HABIT", "Walk"),
                        tuple("A goal with no category", "LOOSE", null, "GOAL", "Solo goal"),
                        tuple("Before the kids wake", "LOOSE", null, "ROUTINE", "Mornings"));
        assertThat(questions(owner)).as("Run's open question went with Run; Walk's answer stays")
                .extracting(row -> row.get("subject_id"), row -> row.get("status"))
                .doesNotContain(tuple(run, "OPEN"))
                .contains(tuple(walk, "ANSWERED"));

        // On the page: the category's own notes first, then each deleted thing as a subject of its own.
        SecondBrainMemoryGroupDTO healthGroup = service.overview(owner).memory().get(0);
        assertThat(healthGroup.categoryId()).isEqualTo(health);
        assertThat(healthGroup.subjects()).extracting(SecondBrainMemorySubjectDTO::type, SecondBrainMemorySubjectDTO::id,
                SecondBrainMemorySubjectDTO::name, SecondBrainMemorySubjectDTO::formerType, SecondBrainMemorySubjectDTO::iconId)
                .containsExactly(
                        tuple(SecondBrainSubjectType.CATEGORY, health, "health", null, "lucide:heart"),
                        tuple(SecondBrainSubjectType.CATEGORY, health, "Run", SecondBrainSubjectType.HABIT, null),
                        tuple(SecondBrainSubjectType.CATEGORY, health, "Walk", SecondBrainSubjectType.HABIT, null));

        // The category goes too: its notes and the ones handed to it turn loose, names kept.
        categoryService.deleteCategory(health.toString(), owner.getId());
        assertThat(noteRows(owner)).extracting(row -> row.get("text"), row -> row.get("subject_type"),
                row -> row.get("former_type"), row -> row.get("subject_name"))
                .contains(
                        tuple("Health matters more now", "LOOSE", null, "health"),
                        tuple("Rain stops it", "LOOSE", "HABIT", "Run"));
    }

    /**
     * The move runs in the delete's transaction. A category a habit still uses is refused, and
     * its notes must still be on it afterwards, not loose under a category that is still there.
     */
    @Test
    @DisplayName("a delete that is refused leaves the notes where they were")
    void aRefusedDeleteMovesNothing() {
        User owner = newUser("en");
        UUID mind = category(owner, "Mind");
        habit(owner, "Read", mind);
        add(owner, SecondBrainSubjectType.CATEGORY, mind, "Reading more this year", SecondBrainNoteOrigin.ANSWER);

        assertErrorKey(() -> categoryService.deleteCategory(mind.toString(), owner.getId()), ErrorKey.CATEGORY_IN_USE);

        assertThat(noteRows(owner)).extracting(row -> row.get("subject_type"), row -> row.get("subject_id"))
                .containsExactly(tuple("CATEGORY", mind));
    }

    // ---- what the follow-ups and the overview read ----

    @Test
    @DisplayName("the prompt data carries the newest three notes of a thing named on the page before the most recent others")
    void promptDataCarriesTheNotesOfNamedThingsFirst() {
        User owner = newUser("en");
        UUID mind = category(owner, "Mind");
        UUID books = goal(owner, "Read twelve books", mind);
        // The goal is created on the real date, so the notes are dated from it too.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant twoDaysAgo = today.minusDays(2).atTime(9, 0).toInstant(ZoneOffset.UTC);
        for (int i = 1; i <= 4; i++) {
            setNotesClock(Clock.fixed(twoDaysAgo.plusSeconds(i), ZoneOffset.UTC));
            add(owner, SecondBrainSubjectType.GOAL, books, "books note " + i, SecondBrainNoteOrigin.ANSWER);
        }
        // Twelve things noted after it, which would push it off the list if recency were all.
        for (int i = 1; i <= 12; i++) {
            jdbc.update("INSERT INTO second_brain_notes (id, user_id, subject_type, subject_id, subject_name, text, "
                    + "origin, created_at, updated_at) VALUES (?, ?, 'HABIT', ?, ?, ?, 'ANSWER', ?, ?)",
                    UUID.randomUUID(), owner.getId(), UUID.randomUUID(), "Habit " + i, "habit note " + i,
                    java.sql.Timestamp.from(twoDaysAgo.plusSeconds(100 + i)),
                    java.sql.Timestamp.from(twoDaysAgo.plusSeconds(100 + i)));
        }

        String data = signals.promptData(owner, today, EnumSet.of(SecondBrainSource.GOALS), List.of());

        assertThat(data).contains("NOTES (what the person told Beyou about their things");
        assertThat(data).contains("- Goal \"Read twelve books\": 2 days ago \"books note 2\"; "
                + "2 days ago \"books note 3\"; 2 days ago \"books note 4\".");
        assertThat(data).doesNotContain("books note 1");
        assertThat(data).contains("\"habit note 12\"").doesNotContain("\"habit note 1\"");
        assertThat(data).as("no date in ISO form").doesNotContainPattern("\\d{4}-\\d{2}-\\d{2}");
    }

    // ---- the old hidden note ----

    @Test
    @DisplayName("the old note shows as sentences, one is kept as a profile line, one discarded, the rest deleted")
    void theOldNoteIsReviewedSentenceBySentence() {
        User owner = newUser("en");
        setUserContext(owner, "Training for a half marathon. Prefers mornings!\nHas two cats?");

        assertThat(service.overview(owner).oldNote())
                .containsExactly("Training for a half marathon.", "Prefers mornings!", "Has two cats?");

        SecondBrainOldNoteResultDTO kept = service.keepOldNoteLine(owner, new SecondBrainOldNoteRequestDTO("Prefers mornings!"));
        assertThat(kept.lines()).containsExactly("Training for a half marathon.", "Has two cats?");
        assertThat(kept.fact().kind()).isEqualTo(SecondBrainFactKind.ABOUT);
        assertThat(kept.fact().origin()).isEqualTo(SecondBrainFactOrigin.OLD_NOTE);
        assertThat(kept.fact().text()).isEqualTo("Prefers mornings!");
        assertThat(userContext(owner)).isEqualTo("Training for a half marathon.\nHas two cats?");

        SecondBrainOldNoteResultDTO discarded = service.discardOldNoteLine(owner, new SecondBrainOldNoteRequestDTO("Has two cats?"));
        assertThat(discarded.lines()).containsExactly("Training for a half marathon.");
        assertThat(discarded.fact()).isNull();
        assertErrorKey(() -> service.discardOldNoteLine(owner, new SecondBrainOldNoteRequestDTO("Has two cats?")),
                ErrorKey.SECOND_BRAIN_OLD_NOTE_LINE_NOT_FOUND);

        service.deleteOldNote(owner);
        assertThat(userContext(owner)).isNull();
        assertThat(service.overview(owner).oldNote()).isEmpty();
        assertThat(service.overview(owner).facts()).extracting(SecondBrainFactDTO::text).containsExactly("Prefers mornings!");
    }

    @Test
    @DisplayName("keeping a sentence with the profile full is a 409 and changes nothing")
    void keepingIntoAFullProfileChangesNothing() {
        User owner = newUser("en");
        setUserContext(owner, "Prefers mornings. Has two cats.");
        for (int i = 0; i < 20; i++) {
            service.createFact(owner, new SecondBrainFactRequestDTO(SecondBrainFactKind.LIKE, "fact " + i));
        }

        assertErrorKey(() -> service.keepOldNoteLine(owner, new SecondBrainOldNoteRequestDTO("Prefers mornings.")),
                ErrorKey.SECOND_BRAIN_FACT_LIMIT_REACHED);

        assertThat(userContext(owner)).isEqualTo("Prefers mornings. Has two cats.");
        assertThat(service.overview(owner).facts()).hasSize(20);
    }

    // ---- helpers ----

    private SecondBrainNoteDTO add(User owner, SecondBrainSubjectType type, UUID thing, String text,
                                   SecondBrainNoteOrigin origin) {
        return notes.add(owner.getId(), type, thing, text, origin).orElseThrow();
    }

    private void setNotesClock(Clock clock) {
        Object target = AopTestUtils.getTargetObject(notes);
        ReflectionTestUtils.setField(target, "clock", clock);
    }

    private void assertErrorKey(ThrowingCallable call, ErrorKey key) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(key);
    }

    private List<Map<String, Object>> questions(User owner) {
        return jdbc.queryForList("SELECT kind, status, subject_type, subject_id, text FROM second_brain_questions "
                + "WHERE user_id = ? ORDER BY created_at, id", owner.getId());
    }

    private List<Map<String, Object>> newThing(User owner) {
        return questions(owner).stream().filter(row -> "NEW_THING".equals(row.get("kind"))).toList();
    }

    private SecondBrainQuestionDTO openNewThing(User owner, UUID thing) {
        return service.refill(owner).stream()
                .filter(question -> question.kind() == SecondBrainQuestionKind.NEW_THING)
                .filter(question -> question.status() == SecondBrainQuestionStatus.OPEN)
                .filter(question -> thing.equals(jdbc.queryForObject(
                        "SELECT subject_id FROM second_brain_questions WHERE id = ?", UUID.class, question.id())))
                .findFirst().orElseThrow();
    }

    private List<Map<String, Object>> noteRows(User owner) {
        return jdbc.queryForList("SELECT text, subject_type, subject_id, former_type, subject_name "
                + "FROM second_brain_notes WHERE user_id = ? ORDER BY created_at, id", owner.getId());
    }

    private void setUserContext(User owner, String context) {
        jdbc.update("UPDATE users SET user_context = ? WHERE id = ?", context, owner.getId());
    }

    private String userContext(User owner) {
        return jdbc.queryForObject("SELECT user_context FROM users WHERE id = ?", String.class, owner.getId());
    }

    private UUID category(User owner, String name) {
        categoryService.createCategory(new CategoryRequestDTO(name, "lucide:heart", "", ExperienceLevel.BEGINNER),
                owner.getId());
        return categoryRepository.findAllByUserId(owner.getId()).orElseThrow().stream()
                .filter(category -> category.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private UUID habit(User owner, String name, UUID... categories) {
        habitService.createHabit(new CreateHabitDTO(name, "", "", "lucide:heart", 3, 3, List.of(categories),
                ExperienceLevel.BEGINNER), owner.getId());
        return habitRepository.findAllByUserId(owner.getId()).stream()
                .filter(habit -> habit.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private UUID goal(User owner, String name, UUID... categories) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        goalService.createGoal(new CreateGoalRequestDTO(name, null, "lucide:flag", 10.0, "books", 0.0,
                List.of(categories), null, today, today.plusMonths(2), GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM,
                null), owner.getId());
        return goalRepository.findAllByUserId(owner.getId()).orElseThrow().stream()
                .filter(goal -> goal.getName().equals(name)).findFirst().orElseThrow().getId();
    }

    private UUID routine(User owner, String name, UUID habit) {
        return routineService.createDiaryRoutine(new DiaryRoutineRequestDTO(name, "lucide:list", RoutineType.LIST,
                null, List.of(new RoutineItemRequestDTO(null, habit, null, null))), owner.getId()).id();
    }

    private User newUser(String language) {
        User account = new User();
        account.setName("brain-memory");
        account.setEmail("brain-memory-" + UUID.randomUUID() + "@test.com");
        account.setPassword("password123");
        account.setGoogleAccount(false);
        account.setTimezone("UTC");
        account.setLanguageInUse(language);
        account.setCompletedDays(new HashSet<>());
        account.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        account.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        User saved = userRepository.saveAndFlush(account);
        accounts.add(saved.getId());
        return saved;
    }
}
