package beyou.beyouapp.backend.integration.notebook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.focus.CycleKind;
import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.RecordCycleRequestDTO;
import beyou.beyouapp.backend.domain.notebook.NotebookPageService;
import beyou.beyouapp.backend.domain.notebook.board.NotebookBoardService;
import beyou.beyouapp.backend.domain.notebook.board.dto.CreateNodeRequestDTO;
import beyou.beyouapp.backend.domain.notebook.card.NotebookCardService;
import beyou.beyouapp.backend.domain.notebook.card.dto.CreateCardRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.BlockMode;
import beyou.beyouapp.backend.domain.notebook.cycle.GoalSource;
import beyou.beyouapp.backend.domain.notebook.cycle.LapBlockState;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleHistoryService;
import beyou.beyouapp.backend.domain.notebook.cycle.StudyCycleService;
import beyou.beyouapp.backend.domain.notebook.cycle.StudySessionRepository;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockPatchDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.BlockRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleConfigRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleDayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.CycleTopicDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.HistoryEntryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionRequestDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.SessionResponseDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyBlockDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyCycleDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyHistoryDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyTodayCycleDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.StudyTodayDTO;
import beyou.beyouapp.backend.domain.notebook.cycle.dto.TodayEntryDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreatePageRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.CreateTopicRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.SetStatusRequestDTO;
import beyou.beyouapp.backend.domain.notebook.dto.StatusChoice;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyOutput;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyOutputRepository;
import beyou.beyouapp.backend.domain.notebook.study.NotebookStudyService;
import beyou.beyouapp.backend.domain.notebook.study.StudyOutputKind;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * Study cycles against a real Postgres: the rules that cross tables (subjects that never overlap,
 * ownership), and the hooks that move a lap from elsewhere in the notebook (a pomodoro, a page
 * marked done, a quiz passed, a session logged and deleted). The arithmetic itself is unit
 * tested in {@code StudyLapTest}; here each test names the wire it guards.
 *
 * <p>Every day of the week carries minutes, so "today" holds whatever day the suite runs on.
 */
@Transactional
class StudyCycleServiceIT extends AbstractIntegrationTest {

    @Autowired private StudyCycleService cycleService;
    @Autowired private StudyCycleHistoryService historyService;
    @Autowired private NotebookPageService pageService;
    @Autowired private NotebookBoardService boardService;
    @Autowired private FocusService focusService;
    @Autowired private NotebookStudyService studyService;
    @Autowired private NotebookStudyOutputRepository outputRepository;
    @Autowired private NotebookCardService cardService;
    @Autowired private StudySessionRepository sessionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private User stranger;

    @BeforeEach
    void seed() {
        user = newUser("cycle-owner");
        stranger = newUser("cycle-stranger");
    }

    // --------------------------------------------------- one active cycle per topic

    @Test
    void aTopicAlreadyInAnActiveCycleIsRefusedWithThatCyclesName() {
        UUID topic = topic("Dir. Constitucional");
        StudyCycleDTO concurso = cycleService.create(user, config("Concurso TRT", BlockMode.TIME, 90, 60, topic(topic)));

        assertThatThrownBy(() -> cycleService.create(user, config("Faculdade", BlockMode.TIME, 90, 60, topic(topic))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_TOPIC_TAKEN);
                    assertThat(e.getDetails()).containsEntry("cycleName", "Concurso TRT")
                            .containsEntry("cycleId", concurso.id().toString())
                            .containsEntry("topicId", topic.toString());
                });
    }

    /** Archiving a cycle frees its topics for another one. */
    @Test
    void archivingFreesTheTopics() {
        UUID topic = topic("Cálculo II");
        StudyCycleDTO first = cycleService.create(user, config("Semestre 1", BlockMode.TIME, 90, 60, topic(topic)));

        cycleService.archive(user, first.id());
        StudyCycleDTO second = cycleService.create(user, config("Semestre 2", BlockMode.TIME, 90, 60, topic(topic)));

        assertThat(second.topics()).extracting(t -> t.topicId()).containsExactly(topic);
        assertThat(cycleService.list(user)).extracting(c -> c.id()).containsExactly(second.id());
    }

    /**
     * The partial unique index is the guarantee when two saves race past the service's check: a
     * second ACTIVE row for a topic fails at the database. An inactive one (an archived cycle's)
     * is fine.
     */
    @Test
    void theDatabaseHoldsOneActiveRowPerTopic() {
        UUID topic = topic("Português");
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.TIME, 90, 60, topic(topic)));
        UUID other = cycleService.create(user, config("Outro", BlockMode.TIME, 90, 60, topic(topic("Inglês")))).id();

        jdbc.update("INSERT INTO study_cycle_topics (id, cycle_id, topic_id, importance, mastery, active, position) "
                + "VALUES (?, ?, ?, 3, 3, false, 9)", UUID.randomUUID(), other, topic);
        assertThatThrownBy(() -> jdbc.update("UPDATE study_cycle_topics SET active = true WHERE cycle_id = ? AND topic_id = ?",
                other, topic)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(cycle.id()).isNotNull();
    }

    // ------------------------------------------------------------- ownership

    @Test
    void anotherUsersCycleIsRefusedEverywhere() {
        UUID topic = topic("Dir. Administrativo");
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.TIME, 90, 60, topic(topic)));
        UUID id = cycle.id();
        UUID block = cycle.blocks().get(0).id();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        List<Runnable> calls = List.of(
                () -> cycleService.get(stranger, id),
                () -> cycleService.update(stranger, id, config("Mine now", BlockMode.TIME, 90, 60, topic(topic))),
                () -> cycleService.replaceBlocks(stranger, id, List.of()),
                () -> cycleService.patchBlock(stranger, id, block, new BlockPatchDTO()),
                () -> cycleService.archive(stranger, id),
                () -> cycleService.delete(stranger, id),
                () -> historyService.history(stranger, id, today, today));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_NOT_OWNED));
        }
        assertThatThrownBy(() -> cycleService.get(user, UUID.randomUUID())).isInstanceOfSatisfying(
                BusinessException.class, e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_NOT_FOUND));
        assertThat(cycleService.today(stranger).cycles()).isEmpty();
    }

    /** Nobody puts somebody else's topic in a cycle, or logs study on it, or deletes their session. */
    @Test
    void anotherUsersTopicsAndSessionsAreRefused() {
        UUID topic = topic("Raciocínio Lógico");
        cycleService.create(user, config("Concurso", BlockMode.TIME, 90, 60, topic(topic)));
        SessionResponseDTO session = cycleService.logSession(user, session(topic, 20, LocalDate.now(ZoneOffset.UTC)));

        assertThatThrownBy(() -> cycleService.create(stranger, config("Theft", BlockMode.TIME, 90, 60, topic(topic))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED));
        assertThatThrownBy(() -> cycleService.logSession(stranger, session(topic, 20, LocalDate.now(ZoneOffset.UTC))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.NOTEBOOK_PAGE_NOT_OWNED));
        assertThatThrownBy(() -> cycleService.deleteSession(stranger, session.id()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_SESSION_NOT_FOUND));
    }

    // ------------------------------------------------------------- subjects

    /**
     * A subject can be any page. With "Implementação" (inside "Software Engineer") as the subject,
     * a pomodoro on a page two levels below it credits its block, and one on the sibling area
     * "Produto" credits nothing. Today's minutes, the cards due and the history count the same
     * subtree the credit does, so the card and the queue never disagree.
     */
    @Test
    void aPageInsideATopicIsASubjectAndOnlyItsSubtreeCounts() {
        UUID root = topic("Software Engineer");
        UUID produto = node(root, "Produto", 0);
        UUID implementacao = node(root, "Implementação", 240);
        UUID testes = node(implementacao, "Testes", 0);
        UUID mocks = page(testes, "Mocks");
        UUID roadmap = node(produto, "Roadmap", 0);
        StudyCycleDTO cycle = cycleService.create(user, config("Senior", BlockMode.TIME, 60, 60,
                new CycleTopicDTO(implementacao, 3, 3, 60)));
        assertThat(cycle.topics()).singleElement().satisfies(t -> {
            assertThat(t.topic().title()).isEqualTo("Implementação");
            assertThat(t.topic().rootTitle()).isEqualTo("Software Engineer");
        });
        card(mocks);
        card(roadmap);

        focusService.recordCycle(user, pomodoro(mocks, 25, CycleKind.POMODORO));
        focusService.recordCycle(user, pomodoro(roadmap, 20, CycleKind.POMODORO));

        assertThat(cycleService.get(user, cycle.id()).blocks().get(0).progress().minutes())
                .as("the page two levels down credits; the sibling area does not").isEqualTo(25);
        StudyTodayCycleDTO today = cycleService.today(user).cycles().get(0);
        assertThat(today.studiedMinutes()).isEqualTo(25);
        assertThat(today.entries()).first().satisfies(e -> assertThat(e.cardsDue()).isEqualTo(1));
        LocalDate day = LocalDate.now(ZoneOffset.UTC);
        StudyHistoryDTO.Day history = historyService.history(user, cycle.id(), day, day).days().get(0);
        assertThat(history.minutes()).isEqualTo(25);
        assertThat(history.byTopic()).containsExactly(new StudyHistoryDTO.TopicMinutes(implementacao, 25));
        assertThat(history.entries()).singleElement().isInstanceOfSatisfying(HistoryEntryDTO.Focus.class, f -> {
            assertThat(f.topic().id()).isEqualTo(implementacao);
            assertThat(f.topic().rootTitle()).isEqualTo("Software Engineer");
            assertThat(f.page().id()).isEqualTo(mocks);
        });
    }

    /** NEXT reads the subject's own roadmap. The topic's would start with Produto, laid out first. */
    @Test
    void aNestedSubjectTakesItsNextConceptFromItsOwnRoadmap() {
        UUID root = topic("Software Engineer");
        UUID produto = node(root, "Produto", 0);
        UUID implementacao = node(root, "Implementação", 240);
        node(produto, "Discovery", 0);
        UUID testes = node(implementacao, "Testes", 0);
        node(implementacao, "Refatoração", 240);

        StudyCycleDTO cycle = cycleService.create(user, config("Senior", BlockMode.GOAL, 60, 60,
                new CycleTopicDTO(implementacao, 3, 3, 60)));

        assertThat(cycle.blocks().get(0).progress().target().id()).isEqualTo(testes);
    }

    /** A block's page is the subject or a page below it. The same topic is not enough any more. */
    @Test
    void aBlocksPageMustLieInsideItsSubject() {
        UUID root = topic("Software Engineer");
        UUID produto = node(root, "Produto", 0);
        UUID implementacao = node(root, "Implementação", 240);
        UUID mocks = page(node(implementacao, "Testes", 0), "Mocks");
        StudyCycleDTO cycle = cycleService.create(user, config("Senior", BlockMode.GOAL, 90, 60, topic(implementacao)));
        UUID blockId = cycle.blocks().get(0).id();

        assertThatThrownBy(() -> cycleService.patchBlock(user, cycle.id(), blockId, aimAt(produto)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.INVALID_REQUEST));
        assertThat(cycleService.patchBlock(user, cycle.id(), blockId, aimAt(mocks)).blocks().get(0).page().id())
                .isEqualTo(mocks);
        assertThat(cycleService.patchBlock(user, cycle.id(), blockId, aimAt(implementacao)).blocks().get(0).page().id())
                .as("the subject itself is allowed").isEqualTo(implementacao);
    }

    /**
     * Subjects never overlap across cycles: one inside another cycle's subject, or one holding it,
     * is refused and the refusal names both. A sibling area is free, and a cycle's own subjects
     * never block its own edit.
     */
    @Test
    void aSubjectThatContainsOrSitsInsideAnotherCyclesSubjectIsTaken() {
        UUID root = topic("Software Engineer");
        UUID implementacao = node(root, "Implementação", 0);
        UUID produto = node(root, "Produto", 240);
        UUID testes = node(implementacao, "Testes", 0);
        StudyCycleDTO areas = cycleService.create(user, config("Areas", BlockMode.TIME, 90, 60, topic(implementacao)));

        for (UUID overlapping : List.of(root, testes)) {
            assertThatThrownBy(() -> cycleService.create(user, config("Other", BlockMode.TIME, 90, 60, topic(overlapping))))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_TOPIC_TAKEN);
                        assertThat(e.getDetails()).containsEntry("topicId", overlapping.toString())
                                .containsEntry("cycleId", areas.id().toString())
                                .containsEntry("cycleName", "Areas")
                                .containsEntry("takenTopicId", implementacao.toString())
                                .containsEntry("takenTopicTitle", "Implementação");
                    });
        }

        StudyCycleDTO product = cycleService.create(user, config("Product", BlockMode.TIME, 90, 60, topic(produto)));
        StudyCycleDTO narrowed = cycleService.update(user, areas.id(), config("Areas", BlockMode.TIME, 90, 60, topic(testes)));
        assertThat(narrowed.topics()).extracting(t -> t.topicId()).containsExactly(testes);
        assertThatThrownBy(() -> cycleService.update(user, areas.id(), config("Areas", BlockMode.TIME, 90, 60, topic(root))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_TOPIC_TAKEN);
                    assertThat(e.getDetails()).containsEntry("cycleId", product.id().toString())
                            .containsEntry("takenTopicId", produto.toString());
                });
    }

    /** Two subjects of one request where one lives inside the other, whichever comes first. */
    @Test
    void twoSubjectsOfOneRequestThatOverlapAreRefused() {
        UUID root = topic("Software Engineer");
        UUID implementacao = node(root, "Implementação", 0);
        UUID testes = node(implementacao, "Testes", 0);

        assertThatThrownBy(() -> cycleService.create(user, config("Mixed", BlockMode.TIME, 90, 60,
                topic(testes), topic(root))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_TOPIC_OVERLAP);
                    assertThat(e.getDetails()).containsEntry("ancestorId", root.toString())
                            .containsEntry("descendantId", testes.toString());
                });
        assertThatThrownBy(() -> cycleService.create(user, config("Mixed", BlockMode.TIME, 90, 60,
                topic(implementacao), topic(testes))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_TOPIC_OVERLAP);
                    assertThat(e.getDetails()).containsEntry("ancestorId", implementacao.toString())
                            .containsEntry("descendantId", testes.toString());
                });
        assertThat(cycleService.list(user)).isEmpty();
    }

    @Test
    void aSessionsPageMustLieInsideItsSubject() {
        UUID root = topic("Software Engineer");
        UUID produto = node(root, "Produto", 0);
        UUID implementacao = node(root, "Implementação", 240);
        UUID testes = node(implementacao, "Testes", 0);
        cycleService.create(user, config("Senior", BlockMode.TIME, 60, 60, new CycleTopicDTO(implementacao, 3, 3, 60)));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThatThrownBy(() -> cycleService.logSession(user, new SessionRequestDTO(implementacao, produto, 20, today, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.INVALID_REQUEST));
        SessionResponseDTO logged = cycleService.logSession(user, new SessionRequestDTO(implementacao, testes, 20, today, null));
        assertThat(logged.credited().minutesAfter()).isEqualTo(20);
    }

    /**
     * A session naming a page inside a subject (the assistant's "20 minutes of Testes") lands on
     * the subject, the row says so, and its delete takes the minutes back from there.
     */
    @Test
    void aSessionNamingAPageInsideASubjectLandsOnTheSubject() {
        UUID root = topic("Software Engineer");
        UUID implementacao = node(root, "Implementação", 0);
        UUID testes = node(implementacao, "Testes", 0);
        StudyCycleDTO cycle = cycleService.create(user, config("Senior", BlockMode.TIME, 60, 60,
                new CycleTopicDTO(implementacao, 3, 3, 60)));

        SessionResponseDTO logged = cycleService.logSession(user, session(testes, 20, LocalDate.now(ZoneOffset.UTC)));

        assertThat(logged.credited().minutesAfter()).isEqualTo(20);
        assertThat(sessionRepository.findById(logged.id())).hasValueSatisfying(row -> {
            assertThat(row.getTopicId()).isEqualTo(implementacao);
            assertThat(row.getPageId()).isEqualTo(testes);
        });
        cycleService.deleteSession(user, logged.id());
        assertThat(cycleService.get(user, cycle.id()).blocks().get(0).progress().minutes()).isZero();
    }

    /**
     * A page linked onto a subject's board from another topic counts under its home, never under
     * the board that shows it: one pomodoro, one subject.
     */
    @Test
    void aLinkedPageCreditsUnderItsHomeNotUnderTheBoardThatShowsIt() {
        UUID root = topic("Software Engineer");
        UUID implementacao = node(root, "Implementação", 0);
        UUID espanhol = topic("Espanhol C1");
        UUID subjuntivo = node(espanhol, "Subjuntivo", 0);
        link(implementacao, subjuntivo, 240);
        card(subjuntivo);
        StudyCycleDTO senior = cycleService.create(user, config("Senior", BlockMode.TIME, 60, 60,
                new CycleTopicDTO(implementacao, 3, 3, 60)));

        focusService.recordCycle(user, pomodoro(subjuntivo, 25, CycleKind.POMODORO));

        assertThat(cycleService.get(user, senior.id()).blocks().get(0).progress().minutes()).isZero();
        StudyTodayCycleDTO today = cycleService.today(user).cycles().get(0);
        assertThat(today.studiedMinutes()).isZero();
        assertThat(today.entries()).first().satisfies(e -> assertThat(e.cardsDue()).isZero());

        StudyCycleDTO idiomas = cycleService.create(user, config("Idiomas", BlockMode.TIME, 60, 60,
                new CycleTopicDTO(espanhol, 3, 3, 60)));
        focusService.recordCycle(user, pomodoro(subjuntivo, 25, CycleKind.POMODORO));

        assertThat(cycleService.get(user, idiomas.id()).blocks().get(0).progress().minutes()).isEqualTo(25);
        assertThat(cycleService.get(user, senior.id()).blocks().get(0).progress().minutes()).isZero();
    }

    // --------------------------------------------------------------- hooks

    /** A pomodoro on any page of the topic credits the topic's first open block and spills. */
    @Test
    void aPomodoroOnAPageCreditsTheTopicsBlocks() {
        UUID topic = topic("Dir. Constitucional");
        UUID page = page(topic, "Controle de constitucionalidade");
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.TIME, 15, 60,
                new CycleTopicDTO(topic, 3, 3, 30)));

        focusService.recordCycle(user, pomodoro(page, 25, CycleKind.POMODORO));
        focusService.recordCycle(user, pomodoro(page, 5, CycleKind.SHORT_BREAK));

        List<StudyBlockDTO> blocks = cycleService.get(user, cycle.id()).blocks();
        assertThat(blocks).extracting(b -> b.progress().minutes()).containsExactly(15, 10);
        assertThat(blocks).extracting(b -> b.progress().state()).containsExactly(LapBlockState.DONE, LapBlockState.OPEN);
    }

    /**
     * The read that writes: GET today fixes the concept the card shows, so marking that page done
     * on the page screen, with no timer at all, closes the block as understood.
     *
     * <p>The second block of the topic is the one under test. The first had its concept fixed by
     * the response to the create, and understanding it leaves the second block open with no
     * concept: only a read can fix one for it before its page is marked done.
     */
    @Test
    void markingTheShownConceptDoneClosesTheBlockAsUnderstood() {
        UUID topic = topic("Dir. Constitucional");
        UUID first = node(topic, "Controle de constitucionalidade", 0);
        UUID second = node(topic, "Direitos fundamentais", 240);
        node(topic, "Organização do Estado", 480);
        UUID other = topic("Português");
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.GOAL, 60, 60,
                new CycleTopicDTO(topic, 5, 1, 120), new CycleTopicDTO(other, 1, 5, 30)));
        assertThat(cycle.blocks().get(0).progress().target().id()).isEqualTo(first);

        pageService.setStatus(user, first, new SetStatusRequestDTO(StatusChoice.DONE));
        // Read straight from the table: GET /cycles/{id} would fix the next concept itself.
        assertThat(jdbc.queryForObject("SELECT state FROM study_cycle_lap_blocks WHERE block_id = ?",
                String.class, cycle.blocks().get(0).id())).isEqualTo("UNDERSTOOD");

        TodayEntryDTO shown = entries(cycleService.today(user)).stream()
                .filter(e -> e.topic().id().equals(topic) && !e.doneToday()).findFirst().orElseThrow();
        assertThat(shown.target().id()).isEqualTo(second);
        assertThat(shown.targetPreview()).as("the read fixed it").isFalse();

        pageService.setStatus(user, second, new SetStatusRequestDTO(StatusChoice.DONE));

        StudyBlockDTO block = cycleService.get(user, cycle.id()).blocks().stream()
                .filter(b -> b.id().equals(shown.blockId())).findFirst().orElseThrow();
        assertThat(block.progress().state()).isEqualTo(LapBlockState.UNDERSTOOD);
        assertThat(block.progress().target().id()).isEqualTo(second);
    }

    /** "Page done, quiz missing": the block waits for the quiz, and the first pass closes it. */
    @Test
    void aBlockThatAsksForTheQuizClosesWhenTheQuizPasses() {
        UUID topic = topic("Português");
        UUID crase = page(topic, "Crase");
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.GOAL, 90, 60, topic(topic)));
        UUID blockId = cycle.blocks().get(0).id();
        BlockPatchDTO patch = new BlockPatchDTO();
        patch.setGoalSource(GoalSource.PAGE);
        patch.setPageId(crase);
        patch.setRequireQuiz(true);
        cycleService.patchBlock(user, cycle.id(), blockId, patch);

        pageService.setStatus(user, crase, new SetStatusRequestDTO(StatusChoice.DONE));
        StudyBlockDTO waiting = cycleService.get(user, cycle.id()).blocks().get(0);
        assertThat(waiting.progress().state()).isEqualTo(LapBlockState.OPEN);
        assertThat(waiting.progress().quizPassed()).isFalse();

        UUID quiz = quiz(crase);
        studyService.grade(user, quiz, List.of(0));

        StudyBlockDTO understood = cycleService.get(user, cycle.id()).blocks().get(0);
        assertThat(understood.progress().state()).isEqualTo(LapBlockState.UNDERSTOOD);
        assertThat(understood.progress().quizPassed()).isTrue();
    }

    // ------------------------------------------------------------------ laps

    /** The last block closing closes the lap: a lap row, the number moves on, overflow stays behind. */
    @Test
    void closingTheLastBlockClosesTheLapAndStartsTheNext() {
        UUID topic = topic("Informática");
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.TIME, 15, 60,
                new CycleTopicDTO(topic, 3, 3, 30)));

        cycleService.logSession(user, session(topic, 40, LocalDate.now(ZoneOffset.UTC)));

        StudyCycleDTO after = cycleService.get(user, cycle.id());
        assertThat(after.lap().number()).isEqualTo(2);
        assertThat(after.lap().minutes()).isZero();
        assertThat(after.blocks()).allSatisfy(b -> assertThat(b.progress().state()).isEqualTo(LapBlockState.OPEN));
        assertThat(after.previousLaps()).singleElement().satisfies(lap -> {
            assertThat(lap.number()).isEqualTo(1);
            assertThat(lap.minutes()).as("the overflow stays in the lap it closed").isEqualTo(40);
            assertThat(lap.plannedMinutes()).isEqualTo(30);
            assertThat(lap.blocksClosed()).isEqualTo(2);
            assertThat(lap.closedAt()).isNotNull();
        });
    }

    /** A session's delete takes its minutes back and reopens the block that falls short. */
    @Test
    void deletingASessionTakesItsMinutesBack() {
        UUID topic = topic("Dir. do Trabalho");
        UUID other = topic("Dir. Penal");
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.TIME, 15, 60,
                new CycleTopicDTO(topic, 3, 3, 30), new CycleTopicDTO(other, 3, 3, 15)));

        SessionResponseDTO logged = cycleService.logSession(user, session(topic, 20, LocalDate.now(ZoneOffset.UTC)));
        assertThat(logged.credited()).isNotNull();
        assertThat(logged.credited().minutesBefore()).isZero();
        assertThat(logged.credited().minutesAfter()).isEqualTo(15);
        assertThat(logged.credited().state()).isEqualTo(LapBlockState.DONE);
        assertThat(logged.credited().cycleName()).isEqualTo("Concurso");

        cycleService.deleteSession(user, logged.id());

        List<StudyBlockDTO> topicBlocks = cycleService.get(user, cycle.id()).blocks().stream()
                .filter(b -> b.topic().id().equals(topic)).toList();
        assertThat(topicBlocks).extracting(b -> b.progress().minutes()).containsExactly(0, 0);
        assertThat(topicBlocks).extracting(b -> b.progress().state()).containsOnly(LapBlockState.OPEN);
    }

    /** A closed lap is history: deleting a session credited to it changes nothing there. */
    @Test
    void deletingASessionOfAClosedLapLeavesTheLapAlone() {
        UUID topic = topic("Química");
        StudyCycleDTO cycle = cycleService.create(user, config("Vestibular", BlockMode.TIME, 15, 60,
                new CycleTopicDTO(topic, 3, 3, 15)));
        SessionResponseDTO closing = cycleService.logSession(user, session(topic, 20, LocalDate.now(ZoneOffset.UTC)));
        cycleService.logSession(user, session(topic, 5, LocalDate.now(ZoneOffset.UTC)));

        cycleService.deleteSession(user, closing.id());

        StudyCycleDTO after = cycleService.get(user, cycle.id());
        assertThat(after.lap().number()).isEqualTo(2);
        assertThat(after.blocks().get(0).progress().minutes()).isEqualTo(5);
        assertThat(after.previousLaps().get(0).minutes()).isEqualTo(20);
    }

    @Test
    void aSessionIsDatedWithinTheLastThirtyDays() {
        UUID topic = topic("Biologia");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        for (LocalDate wrong : List.of(today.plusDays(1), today.minusDays(31))) {
            assertThatThrownBy(() -> cycleService.logSession(user, session(topic, 30, wrong)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_SESSION_DATE));
        }
        SessionResponseDTO kept = cycleService.logSession(user, session(topic, 30, today.minusDays(30)));
        assertThat(kept.credited()).as("no cycle holds the topic, so nothing moved").isNull();
    }

    // -------------------------------------------------------------- editing

    /**
     * Re-planning merges by topic occurrence: the k-th block of a topic keeps the id, the lap
     * progress and the settings of the k-th block of that topic before.
     */
    @Test
    void replanningKeepsEachTopicsBlocksAndTheirSettings() {
        UUID a = topic("A");
        UUID b = topic("B");
        StudyCycleDTO cycle = cycleService.create(user, config("Merge", BlockMode.TIME, 30, 60,
                new CycleTopicDTO(a, 3, 3, 60), new CycleTopicDTO(b, 3, 3, 30)));
        List<UUID> aBlocks = cycle.blocks().stream().filter(x -> x.topic().id().equals(a)).map(StudyBlockDTO::id).toList();
        assertThat(aBlocks).hasSize(2);
        BlockPatchDTO patch = new BlockPatchDTO();
        patch.setNote("questões FCC");
        cycleService.patchBlock(user, cycle.id(), aBlocks.get(0), patch);
        cycleService.logSession(user, session(a, 10, LocalDate.now(ZoneOffset.UTC)));

        StudyCycleDTO replanned = cycleService.update(user, cycle.id(), config("Merge", BlockMode.GOAL, 30, 60,
                new CycleTopicDTO(a, 3, 3, 90), new CycleTopicDTO(b, 5, 1, 30)));

        List<StudyBlockDTO> after = replanned.blocks().stream().filter(x -> x.topic().id().equals(a)).toList();
        assertThat(after).hasSize(3);
        assertThat(after.get(0).id()).isEqualTo(aBlocks.get(0));
        assertThat(after.get(0).note()).isEqualTo("questões FCC");
        assertThat(after.get(0).mode()).as("an existing block keeps its mode").isEqualTo(BlockMode.TIME);
        assertThat(after.get(0).progress().minutes()).isEqualTo(10);
        assertThat(after.get(1).id()).isEqualTo(aBlocks.get(1));
        assertThat(after.get(2).mode()).as("a new block takes the default mode").isEqualTo(BlockMode.GOAL);
        assertThat(after.get(2).goalSource()).isEqualTo(GoalSource.NEXT);
        assertThat(replanned.blocks().get(0).topic().id()).as("B now weighs more and leads").isEqualTo(b);
    }

    /** A rename re-plans nothing, so an order arranged by hand survives it. */
    @Test
    void aRenameKeepsAHandMadeOrder() {
        UUID a = topic("A");
        UUID b = topic("B");
        StudyCycleDTO cycle = cycleService.create(user, config("Order", BlockMode.TIME, 90, 60,
                new CycleTopicDTO(a, 5, 1, 30), new CycleTopicDTO(b, 1, 5, 30)));
        List<StudyBlockDTO> blocks = cycle.blocks();
        List<BlockRequestDTO> reversed = new ArrayList<>();
        for (int i = blocks.size() - 1; i >= 0; i--) {
            StudyBlockDTO x = blocks.get(i);
            reversed.add(new BlockRequestDTO(x.id(), x.topic().id(), x.mode(), x.plannedMinutes(), x.goalSource(),
                    null, false, null));
        }
        cycleService.replaceBlocks(user, cycle.id(), reversed);

        StudyCycleDTO renamed = cycleService.update(user, cycle.id(), config("Order, renamed", BlockMode.TIME, 90, 60,
                new CycleTopicDTO(a, 5, 1, 30), new CycleTopicDTO(b, 1, 5, 30)));

        assertThat(renamed.name()).isEqualTo("Order, renamed");
        assertThat(renamed.blocks()).extracting(x -> x.topic().id()).containsExactly(b, a);
    }

    @Test
    void aBlockCannotAimAtAPageOfAnotherTopic() {
        UUID a = topic("A");
        UUID elsewhere = page(topic("B"), "Somewhere else");
        StudyCycleDTO cycle = cycleService.create(user, config("Pages", BlockMode.GOAL, 90, 60, topic(a)));
        BlockPatchDTO patch = new BlockPatchDTO();
        patch.setGoalSource(GoalSource.PAGE);
        patch.setPageId(elsewhere);

        assertThatThrownBy(() -> cycleService.patchBlock(user, cycle.id(), cycle.blocks().get(0).id(), patch))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.INVALID_REQUEST));
    }

    @Test
    void tenActiveCyclesAndSixtyBlocksAreTheLimits() {
        for (int i = 0; i < 10; i++) {
            cycleService.create(user, config("Cycle " + i, BlockMode.TIME, 90, 60, topic(topic("T" + i))));
        }
        assertThatThrownBy(() -> cycleService.create(user, config("Eleventh", BlockMode.TIME, 90, 60, topic(topic("T10")))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_LIMIT));

        User other = newUser("many-blocks");
        UUID big = pageService.createTopic(other, new CreateTopicRequestDTO("Big", null, null, null, null, null)).id();
        assertThatThrownBy(() -> cycleService.create(other, config("Too many", BlockMode.TIME, 15, 60,
                new CycleTopicDTO(big, 3, 3, 600), new CycleTopicDTO(
                        pageService.createTopic(other, new CreateTopicRequestDTO("Big 2", null, null, null, null, null)).id(),
                        3, 3, 600))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.STUDY_CYCLE_LIMIT));
    }

    // ----------------------------------------------------------------- today

    /** The day's clock starts at the start time plus what was already studied today. */
    @Test
    void todaysClockStartsAfterWhatWasAlreadyStudied() {
        UUID topic = topic("Dir. Constitucional");
        UUID page = page(topic, "Controle difuso");
        cycleService.create(user, config("Concurso", BlockMode.TIME, 90, 120, "19:00",
                new CycleTopicDTO(topic, 3, 3, 150)));
        focusService.recordCycle(user, pomodoro(page, 25, CycleKind.POMODORO));

        StudyTodayDTO today = cycleService.today(user);

        StudyTodayCycleDTO cycle = today.cycles().get(0);
        assertThat(cycle.studiedMinutes()).isEqualTo(25);
        assertThat(cycle.startTime()).isEqualTo("19:00");
        List<TodayEntryDTO> open = cycle.entries().stream().filter(e -> !e.doneToday()).toList();
        assertThat(open.get(0).start()).isEqualTo("19:25");
        assertThat(open.stream().mapToInt(TodayEntryDTO::minutesToday).sum()).isEqualTo(120 - 25);
        assertThat(today.plannedMinutes()).isEqualTo(120);
        assertThat(today.studiedMinutes()).isEqualTo(25);
    }

    @Test
    void aCycleWithNoMinutesTodayIsResting() {
        UUID topic = topic("Descanso");
        StudyCycleDTO cycle = cycleService.create(user, config("Rest", BlockMode.TIME, 90, 0, topic(topic)));

        StudyTodayDTO today = cycleService.today(user);

        assertThat(today.cycles()).isEmpty();
        assertThat(today.restingCycles()).singleElement().satisfies(r -> assertThat(r.cycleId()).isEqualTo(cycle.id()));
    }

    // --------------------------------------------------------------- history

    @Test
    void theHistoryHasEveryKindOfEntryOnItsDay() {
        UUID topic = topic("Dir. Constitucional");
        UUID page = node(topic, "Controle de constitucionalidade", 0);
        StudyCycleDTO cycle = cycleService.create(user, config("Concurso", BlockMode.GOAL, 90, 60,
                new CycleTopicDTO(topic, 3, 3, 60)));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        focusService.recordCycle(user, pomodoro(page, 25, CycleKind.POMODORO));
        SessionResponseDTO session = cycleService.logSession(user, session(topic, 10, today.minusDays(2)));
        pageService.setStatus(user, page, new SetStatusRequestDTO(StatusChoice.DONE));

        StudyHistoryDTO history = historyService.history(user, cycle.id(), today.minusDays(6), today);

        assertThat(history.days()).hasSize(7);
        StudyHistoryDTO.Day day = history.days().get(6);
        assertThat(day.date()).isEqualTo(today);
        assertThat(day.plannedMinutes()).isEqualTo(60);
        assertThat(day.minutes()).isEqualTo(25);
        assertThat(day.entries()).extracting(HistoryEntryDTO::kind).contains("FOCUS", "UNDERSTOOD", "LAP_CLOSED");
        StudyHistoryDTO.Day backdated = history.days().get(4);
        assertThat(backdated.entries()).singleElement().isInstanceOfSatisfying(HistoryEntryDTO.Manual.class,
                m -> assertThat(m.sessionId()).isEqualTo(session.id()));
        assertThat(history.topics()).extracting(t -> t.id()).containsExactly(topic);

        assertThatThrownBy(() -> historyService.history(user, cycle.id(), today.minusDays(31), today))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorKey()).isEqualTo(ErrorKey.INVALID_REQUEST));
    }

    // --------------------------------------------------------------- helpers

    private UUID topic(String title) {
        return pageService.createTopic(user, new CreateTopicRequestDTO(title, null, null, null, null, null)).id();
    }

    private UUID page(UUID topic, String title) {
        return pageService.createPage(user, new CreatePageRequestDTO(topic, title, null)).id();
    }

    private UUID node(UUID board, String title, double x) {
        return boardService.addNode(user, board, new CreateNodeRequestDTO(null, title, null, null, x, 0d, null, null, null))
                .node().pageId();
    }

    private void link(UUID board, UUID page, double x) {
        boardService.addNode(user, board, new CreateNodeRequestDTO(null, null, page, null, x, 0d, null, null, null));
    }

    private void card(UUID page) {
        cardService.create(user, page, new CreateCardRequestDTO("Front", "Back", null));
    }

    private static BlockPatchDTO aimAt(UUID page) {
        BlockPatchDTO patch = new BlockPatchDTO();
        patch.setGoalSource(GoalSource.PAGE);
        patch.setPageId(page);
        return patch;
    }

    private UUID quiz(UUID page) {
        NotebookStudyOutput output = new NotebookStudyOutput();
        output.setUser(user);
        output.setPageId(page);
        output.setKind(StudyOutputKind.QUIZ);
        output.setTitle("Crase quiz");
        output.setContent("{\"questions\":[{\"question\":\"Crase?\",\"options\":[\"a\",\"b\",\"c\",\"d\"],"
                + "\"answerIndex\":0,\"explanation\":\"a\",\"citation\":null}]}");
        output.setCreatedAt(Instant.now());
        return outputRepository.save(output).getId();
    }

    private static CycleTopicDTO topic(UUID id) {
        return new CycleTopicDTO(id, 3, 3, null);
    }

    private static CycleConfigRequestDTO config(String name, BlockMode mode, int maxBlock, int dayMinutes,
            CycleTopicDTO... topics) {
        return config(name, mode, maxBlock, dayMinutes, null, topics);
    }

    private static CycleConfigRequestDTO config(String name, BlockMode mode, int maxBlock, int dayMinutes,
            String start, CycleTopicDTO... topics) {
        List<CycleDayDTO> days = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            days.add(new CycleDayDTO(day, dayMinutes, start));
        }
        return new CycleConfigRequestDTO(name, mode, maxBlock, days, List.of(topics));
    }

    private static SessionRequestDTO session(UUID topic, int minutes, LocalDate day) {
        return new SessionRequestDTO(topic, null, minutes, day, null);
    }

    private static RecordCycleRequestDTO pomodoro(UUID page, int minutes, CycleKind kind) {
        Instant end = Instant.now();
        return new RecordCycleRequestDTO(null, kind, end.minusSeconds(minutes * 60L), end, minutes, page);
    }

    private static List<TodayEntryDTO> entries(StudyTodayDTO today) {
        return today.cycles().get(0).entries();
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
