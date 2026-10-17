package beyou.beyouapp.backend.integration.quicknote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.quicknote.QuickNote;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteColor;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteRepository;
import beyou.beyouapp.backend.domain.quicknote.QuickNoteService;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteCreateRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteEditRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteLayoutRequestDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNotePositionDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteResponseDTO;
import beyou.beyouapp.backend.domain.quicknote.dto.QuickNoteTaskConversionDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.domain.task.dto.TaskResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

/**
 * The Ideas board's rules against a real Postgres: placement, the limit, ownership, the
 * all-or-nothing layout, tidy, archive and restore, and the conversion to a task.
 *
 * <p>Transactional, so every case runs in one transaction and rolls back. The races (a double
 * submit on the conversion, two creates at once) need committed rows and real threads, and live
 * in {@link QuickNoteConcurrencyIT}.
 */
@Transactional
class QuickNoteServiceIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Autowired private QuickNoteService service;
    @Autowired private QuickNoteRepository noteRepository;
    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User owner;
    private User stranger;

    @BeforeEach
    void seed() {
        owner = newUser("ideas-owner");
        stranger = newUser("ideas-stranger");
        setClock(NOW);
    }

    // ---- create and placement ----

    @Test
    @DisplayName("a note without a spot takes the first free cell (here the gap at cell 1) and goes in front")
    void createWithoutASpotFillsTheFirstFreeCellInFront() {
        QuickNoteResponseDTO first = create(owner, "first", QuickNoteColor.BLUE, 0, 0);
        QuickNoteResponseDTO third = create(owner, "third", null, 448, 0);

        QuickNoteResponseDTO second = create(owner, "  second idea \n", null, null, null);

        assertThat(first.z()).isZero();
        assertThat(third.z()).isEqualTo(1);
        assertThat(second.x()).isEqualTo(224);
        assertThat(second.y()).isZero();
        assertThat(second.z()).as("one above the highest on the board").isEqualTo(2);
        assertThat(second.color()).as("no colour is yellow").isEqualTo(QuickNoteColor.YELLOW);
        assertThat(second.text()).isEqualTo("second idea");
        assertThat(second.archivedAt()).isNull();
        assertThat(second.convertedTaskId()).isNull();
        assertThat(second.createdAt()).isEqualTo(NOW);
        assertThat(second.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("half a coordinate is no coordinate: the note is placed")
    void halfACoordinateIsIgnored() {
        create(owner, "on the origin", null, 0, 0);

        QuickNoteResponseDTO onlyX = create(owner, "only x", QuickNoteColor.PINK, 3000, null);
        QuickNoteResponseDTO onlyY = create(owner, "only y", null, null, 3000);

        assertThat(onlyX.x()).isEqualTo(224);
        assertThat(onlyX.y()).isZero();
        assertThat(onlyY.x()).isEqualTo(448);
        assertThat(onlyX.color()).isEqualTo(QuickNoteColor.PINK);
    }

    @Test
    @DisplayName("someone else's notes and the owner's archived ones neither block a cell nor raise z")
    void onlyTheOwnersActiveNotesShapeANewNote() {
        create(stranger, "theirs", null, 0, 0);
        QuickNoteResponseDTO archived = create(owner, "put away", null, 0, 0);
        service.archive(owner, archived.id());

        QuickNoteResponseDTO fresh = create(owner, "new", null, null, null);

        assertThat(fresh.x()).isZero();
        assertThat(fresh.y()).isZero();
        assertThat(fresh.z()).isZero();
    }

    // ---- the limit ----

    /**
     * 199 on the board and three archived. The 200th fits, which a count of every note would
     * refuse; the 201st does not, which a {@code >} instead of {@code >=} would let through.
     */
    @Test
    @DisplayName("200 active notes and no more; archived ones do not count, and restore obeys the limit")
    void theLimitCountsActiveNotesOnly() {
        List<QuickNote> seeded = new ArrayList<>();
        for (int i = 0; i < QuickNote.MAX_ACTIVE - 1; i++) {
            seeded.add(note(owner, QuickNoteColor.YELLOW, i, null));
        }
        List<QuickNote> archived = List.of(
                note(owner, QuickNoteColor.PINK, 0, NOW), note(owner, QuickNoteColor.PINK, 0, NOW),
                note(owner, QuickNoteColor.PINK, 0, NOW));
        seeded.addAll(archived);
        noteRepository.saveAllAndFlush(seeded);

        QuickNoteResponseDTO twoHundredth = create(owner, "the 200th", null, null, null);
        assertThat(twoHundredth.z()).as("in front of the 199 seeded at z 0..198").isEqualTo(199);

        assertErrorKey(() -> create(owner, "the 201st", null, null, null), ErrorKey.QUICK_NOTE_LIMIT_REACHED);
        assertErrorKey(() -> service.restore(owner, archived.get(0).getId()), ErrorKey.QUICK_NOTE_LIMIT_REACHED);
        assertThat(reload(archived.get(0).getId()).getArchivedAt()).as("the refused restore left it archived")
                .isNotNull();

        service.archive(owner, twoHundredth.id());
        QuickNoteResponseDTO afterMakingRoom = create(owner, "room again", null, null, null);
        assertThat(afterMakingRoom.id()).isNotNull();
        assertThat(noteRepository.findByUserIdAndArchivedAtIsNullOrderByZAscCreatedAtAsc(owner.getId()))
                .hasSize(QuickNote.MAX_ACTIVE);
    }

    // ---- ownership ----

    @Test
    @DisplayName("someone else's note answers not-found on every route and stays untouched")
    void someoneElsesNoteIsNotFoundEverywhere() {
        QuickNoteResponseDTO mine = create(owner, "private idea", QuickNoteColor.GREEN, 10, 20);
        UUID id = mine.id();

        assertNotFound(() -> service.edit(stranger, id, new QuickNoteEditRequestDTO("mine now", QuickNoteColor.PINK)));
        assertNotFound(() -> service.archive(stranger, id));
        assertNotFound(() -> service.restore(stranger, id));
        assertNotFound(() -> service.delete(stranger, id));
        assertNotFound(() -> service.convertToTask(stranger, id, taskRequest("Steal it")));
        assertNotFound(() -> service.saveLayout(stranger, layout(new QuickNotePositionDTO(id, 999, 999, 9))));

        QuickNote stored = reload(id);
        assertThat(stored.getText()).isEqualTo("private idea");
        assertThat(stored.getColor()).isEqualTo(QuickNoteColor.GREEN);
        assertThat(stored.getX()).isEqualTo(10);
        assertThat(stored.getArchivedAt()).isNull();
        assertThat(stored.getConvertedTaskId()).isNull();
        assertThat(tasksOf(stranger)).as("the refused conversion made no task").isEmpty();
    }

    @Test
    @DisplayName("an id that does not exist answers the same way as someone else's")
    void unknownIdIsNotFound() {
        assertNotFound(() -> service.archive(owner, UUID.randomUUID()));
    }

    // ---- layout ----

    @Test
    @DisplayName("the layout saves exactly what was sent and answers the board in its new stacking order")
    void layoutSavesWhatWasSent() {
        QuickNoteResponseDTO a = create(owner, "a", null, 0, 0);
        QuickNoteResponseDTO b = create(owner, "b", null, 224, 0);
        QuickNoteResponseDTO c = create(owner, "c", null, 448, 0);

        List<QuickNoteResponseDTO> board = service.saveLayout(owner, layout(
                new QuickNotePositionDTO(a.id(), 1000, -500, 7),
                new QuickNotePositionDTO(c.id(), -100000, 100000, 3)));

        assertThat(board).extracting(QuickNoteResponseDTO::id).containsExactly(b.id(), c.id(), a.id());
        QuickNote movedA = reload(a.id());
        assertThat(movedA.getX()).isEqualTo(1000);
        assertThat(movedA.getY()).isEqualTo(-500);
        assertThat(movedA.getZ()).isEqualTo(7);
        assertThat(reload(c.id()).getX()).isEqualTo(-100000);
    }

    @Test
    @DisplayName("one foreign id in a layout refuses the whole save: nothing moves")
    void layoutIsAllOrNothingOnAForeignId() {
        QuickNoteResponseDTO mine = create(owner, "mine", null, 0, 0);
        QuickNoteResponseDTO theirs = create(stranger, "theirs", null, 0, 0);

        assertNotFound(() -> service.saveLayout(owner, layout(
                new QuickNotePositionDTO(mine.id(), 1000, 1000, 9),
                new QuickNotePositionDTO(theirs.id(), 50, 50, 1))));

        QuickNote stored = reload(mine.id());
        assertThat(stored.getX()).isZero();
        assertThat(stored.getY()).isZero();
        assertThat(stored.getZ()).isZero();
        assertThat(reload(theirs.id()).getX()).isZero();
    }

    @Test
    @DisplayName("an archived note in a layout refuses the whole save too")
    void layoutRefusesAnArchivedNote() {
        QuickNoteResponseDTO active = create(owner, "active", null, 0, 0);
        QuickNoteResponseDTO archived = create(owner, "archived", null, 224, 0);
        service.archive(owner, archived.id());

        assertNotFound(() -> service.saveLayout(owner, layout(
                new QuickNotePositionDTO(active.id(), 700, 700, 4),
                new QuickNotePositionDTO(archived.id(), 0, 0, 5))));

        assertThat(reload(active.id()).getX()).isZero();
    }

    @Test
    @DisplayName("the same note twice in one layout is a bad request")
    void layoutRefusesADuplicateId() {
        QuickNoteResponseDTO a = create(owner, "a", null, 0, 0);

        assertErrorKey(() -> service.saveLayout(owner, layout(
                new QuickNotePositionDTO(a.id(), 1, 1, 1),
                new QuickNotePositionDTO(a.id(), 2, 2, 2))), ErrorKey.INVALID_REQUEST);
    }

    // ---- tidy ----

    @Test
    @DisplayName("tidy lays the board out by colour order, then oldest first, and leaves the archive alone")
    void tidyGroupsByColourThenAge() {
        QuickNoteResponseDTO blue = createAt(NOW, "blue", QuickNoteColor.BLUE, 900, 900);
        QuickNoteResponseDTO yellowOlder = createAt(NOW.plusSeconds(60), "yellow 1", QuickNoteColor.YELLOW, -300, 40);
        QuickNoteResponseDTO pink = createAt(NOW.plusSeconds(120), "pink", QuickNoteColor.PINK, 5, 5);
        QuickNoteResponseDTO yellowNewer = createAt(NOW.plusSeconds(180), "yellow 2", QuickNoteColor.YELLOW, 70, 70);
        QuickNoteResponseDTO archived = createAt(NOW.plusSeconds(240), "orange", QuickNoteColor.ORANGE, 1234, 4321);
        service.archive(owner, archived.id());
        setClock(NOW.plusSeconds(600));

        List<QuickNoteResponseDTO> board = service.tidy(owner);

        assertThat(board).extracting(QuickNoteResponseDTO::id)
                .containsExactly(yellowOlder.id(), yellowNewer.id(), pink.id(), blue.id());
        assertThat(board).extracting(QuickNoteResponseDTO::x).containsExactly(0, 224, 448, 672);
        assertThat(board).extracting(QuickNoteResponseDTO::y).containsOnly(0);
        assertThat(board).extracting(QuickNoteResponseDTO::z).containsExactly(0, 1, 2, 3);
        assertThat(board).extracting(QuickNoteResponseDTO::updatedAt).containsOnly(NOW.plusSeconds(600));

        QuickNote stillArchived = reload(archived.id());
        assertThat(stillArchived.getX()).isEqualTo(1234);
        assertThat(stillArchived.getY()).isEqualTo(4321);
        assertThat(service.listActive(owner)).extracting(QuickNoteResponseDTO::id)
                .as("the stored order is the tidied one")
                .containsExactly(yellowOlder.id(), yellowNewer.id(), pink.id(), blue.id());
    }

    // ---- edit, archive, restore, delete ----

    @Test
    @DisplayName("editing replaces the words and the colour, trimmed, and leaves the spot")
    void editReplacesTextAndColour() {
        QuickNoteResponseDTO note = create(owner, "draft", null, 30, 40);
        setClock(NOW.plusSeconds(60));

        QuickNoteResponseDTO edited = service.edit(owner, note.id(),
                new QuickNoteEditRequestDTO("  call Ana\nabout the trip  ", QuickNoteColor.PURPLE));

        assertThat(edited.text()).isEqualTo("call Ana\nabout the trip");
        assertThat(edited.color()).isEqualTo(QuickNoteColor.PURPLE);
        assertThat(edited.x()).isEqualTo(30);
        assertThat(edited.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("archiving twice keeps the first time")
    void archiveIsIdempotent() {
        QuickNoteResponseDTO note = create(owner, "later", null, 0, 0);

        service.archive(owner, note.id());
        setClock(NOW.plusSeconds(600));
        QuickNoteResponseDTO again = service.archive(owner, note.id());

        assertThat(again.archivedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("restoring puts the note back where it was, in front of the others")
    void restorePutsItBackInFront() {
        QuickNoteResponseDTO a = create(owner, "a", null, 300, 400);
        create(owner, "b", null, 0, 0);
        create(owner, "c", null, 224, 0);
        service.archive(owner, a.id());

        QuickNoteResponseDTO restored = service.restore(owner, a.id());

        assertThat(restored.archivedAt()).isNull();
        assertThat(restored.x()).isEqualTo(300);
        assertThat(restored.y()).isEqualTo(400);
        assertThat(restored.z()).as("b and c sit at 1 and 2").isEqualTo(3);
    }

    @Test
    @DisplayName("restoring a note already on the board changes nothing")
    void restoreOfAnActiveNoteChangesNothing() {
        QuickNoteResponseDTO a = create(owner, "a", null, 0, 0);
        create(owner, "b", null, 224, 0);
        setClock(NOW.plusSeconds(60));

        QuickNoteResponseDTO same = service.restore(owner, a.id());

        assertThat(same.z()).isZero();
        assertThat(same.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("deleting removes the note, archived or not")
    void deleteRemovesTheNote() {
        QuickNoteResponseDTO note = create(owner, "gone", null, 0, 0);
        service.archive(owner, note.id());

        service.delete(owner, note.id());
        entityManager.flush();

        assertThat(noteRepository.findById(note.id())).isEmpty();
    }

    // ---- lists ----

    @Test
    @DisplayName("the board reads back to front, oldest first among equals; the archive newest first, at most 100")
    void listOrders() {
        noteRepository.saveAllAndFlush(List.of(
                withCreated(note(owner, QuickNoteColor.BLUE, 5, null), NOW),
                withCreated(note(owner, QuickNoteColor.BLUE, 2, null), NOW.plusSeconds(10)),
                withCreated(note(owner, QuickNoteColor.BLUE, 2, null), NOW.minusSeconds(10))));
        List<QuickNote> archive = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            archive.add(note(owner, QuickNoteColor.PINK, 0, NOW.minusSeconds(i)));
        }
        noteRepository.saveAllAndFlush(archive);

        List<QuickNoteResponseDTO> board = service.listActive(owner);
        assertThat(board).extracting(QuickNoteResponseDTO::z).containsExactly(2, 2, 5);
        assertThat(board.get(0).createdAt()).isEqualTo(NOW.minusSeconds(10));

        List<QuickNoteResponseDTO> archived = service.listArchived(owner);
        assertThat(archived).hasSize(100);
        assertThat(archived.get(0).archivedAt()).isEqualTo(NOW);
        assertThat(archived.get(99).archivedAt()).as("the oldest one is the one left out")
                .isEqualTo(NOW.minusSeconds(99));
    }

    // ---- convert to a task ----

    @Test
    @DisplayName("making it a task creates the task, archives the note pointing at it, and the task list sees it")
    void convertCreatesTheTaskAndArchivesTheNote() {
        QuickNoteResponseDTO note = create(owner, "call the plumber\nabout the sink", null, 0, 0);
        // Warm the task cache, so a missing eviction would leave the new task out of the list.
        assertThat(taskService.getAllTasks(owner.getId())).isEmpty();

        QuickNoteTaskConversionDTO converted = service.convertToTask(owner, note.id(),
                new CreateTaskRequestDTO("call the plumber", "about the sink", "lucide:wrench", null, null, null, true));

        assertThat(converted.taskId()).isNotNull();
        assertThat(converted.note().archivedAt()).isEqualTo(NOW);
        assertThat(converted.note().convertedTaskId()).isEqualTo(converted.taskId());

        entityManager.flush();
        Task task = taskRepository.findById(converted.taskId()).orElseThrow();
        assertThat(task.getName()).isEqualTo("call the plumber");
        assertThat(task.getDescription()).isEqualTo("about the sink");
        assertThat(task.getUser().getId()).isEqualTo(owner.getId());

        assertThat(taskService.getAllTasks(owner.getId())).extracting(TaskResponseDTO::id)
                .as("tasks are cached per user; the conversion has to evict")
                .containsExactly(converted.taskId());
        assertThat(service.listActive(owner)).isEmpty();
        assertThat(service.listArchived(owner)).extracting(QuickNoteResponseDTO::convertedTaskId)
                .containsExactly(converted.taskId());
    }

    @Test
    @DisplayName("a note converts once: the second time is a conflict and no second task appears")
    void convertingTwiceIsAConflict() {
        QuickNoteResponseDTO note = create(owner, "book flights", null, 0, 0);
        UUID taskId = service.convertToTask(owner, note.id(), taskRequest("book flights")).taskId();

        assertErrorKey(() -> service.convertToTask(owner, note.id(), taskRequest("book flights again")),
                ErrorKey.QUICK_NOTE_ALREADY_CONVERTED);

        assertThat(tasksOf(owner)).extracting(Task::getId).containsExactly(taskId);
        assertThat(reload(note.id()).getConvertedTaskId()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("a converted note restored to the board still cannot convert again")
    void aRestoredConvertedNoteStaysConverted() {
        QuickNoteResponseDTO note = create(owner, "renew the passport", null, 0, 0);
        service.convertToTask(owner, note.id(), taskRequest("renew the passport"));
        service.restore(owner, note.id());

        assertErrorKey(() -> service.convertToTask(owner, note.id(), taskRequest("again")),
                ErrorKey.QUICK_NOTE_ALREADY_CONVERTED);
        assertThat(tasksOf(owner)).hasSize(1);
    }

    @Test
    @DisplayName("deleting the task it became unlinks the note instead of blocking the delete")
    void deletingTheTaskNullsTheLink() {
        QuickNoteResponseDTO note = create(owner, "fix the bike", null, 0, 0);
        UUID taskId = service.convertToTask(owner, note.id(), taskRequest("fix the bike")).taskId();
        entityManager.flush();

        taskService.deleteTask(taskId, owner.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(noteRepository.findById(note.id()).orElseThrow().getConvertedTaskId()).isNull();
    }

    // ---- helpers ----

    private QuickNoteResponseDTO create(User user, String text, QuickNoteColor color, Integer x, Integer y) {
        return service.create(user, new QuickNoteCreateRequestDTO(text, color, x, y));
    }

    private QuickNoteResponseDTO createAt(Instant at, String text, QuickNoteColor color, int x, int y) {
        setClock(at);
        return create(owner, text, color, x, y);
    }

    private static QuickNoteLayoutRequestDTO layout(QuickNotePositionDTO... positions) {
        return new QuickNoteLayoutRequestDTO(List.of(positions));
    }

    private static CreateTaskRequestDTO taskRequest(String name) {
        return new CreateTaskRequestDTO(name, null, "lucide:check", null, null, null, false);
    }

    /** A row written straight through the repository, for boards too big to build one create at a time. */
    private QuickNote note(User user, QuickNoteColor color, int z, Instant archivedAt) {
        QuickNote note = new QuickNote();
        note.setUser(user);
        note.setText(color + " seeded");
        note.setColor(color);
        note.setX(0);
        note.setY(0);
        note.setZ(z);
        note.setArchivedAt(archivedAt);
        note.setCreatedAt(NOW);
        note.setUpdatedAt(NOW);
        return note;
    }

    private static QuickNote withCreated(QuickNote note, Instant createdAt) {
        note.setCreatedAt(createdAt);
        return note;
    }

    private List<Task> tasksOf(User user) {
        entityManager.flush();
        return taskRepository.findAllByUserId(user.getId()).orElse(List.of());
    }

    private QuickNote reload(UUID id) {
        entityManager.flush();
        entityManager.clear();
        return noteRepository.findById(id).orElseThrow();
    }

    private void assertNotFound(ThrowingCallable call) {
        assertErrorKey(call, ErrorKey.QUICK_NOTE_NOT_FOUND);
    }

    private void assertErrorKey(ThrowingCallable call, ErrorKey key) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(key);
    }

    private void setClock(Instant instant) {
        Object target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(instant, ZoneOffset.UTC));
    }

    private User newUser(String tag) {
        User u = new User();
        u.setName(tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("America/Sao_Paulo");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
