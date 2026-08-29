package beyou.beyouapp.backend.integration.focus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.focus.CycleKind;
import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.CreateMicroTaskRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusCycleResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusDayResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
import beyou.beyouapp.backend.domain.focus.dto.RecordCycleRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.ReorderMicroTasksRequestDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotCheckResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

/**
 * The Focus Mode's history, against a real Postgres.
 *
 * <p>Real rather than mocked because the two rules that matter here are DATABASE rules: the unique
 * constraint that makes materialising a pinned template idempotent, and the FK path through the
 * item's routine that decides who owns what. A mock cannot fail either of those.
 *
 * <p>The user's specification, verbatim, is what the micro-task tests pin: "Cada micro task é por
 * item da rotina. Se eu mudo o item, não deve prosseguir com as micro tasks (ao menos que esteja
 * fixada; se estiver fixada, ao mudar para outro item, ele é criado e vinculado ao novo item
 * também)."
 */
@Transactional
class FocusServiceIT extends AbstractIntegrationTest {

    @Autowired private FocusService focusService;
    @Autowired private SnapshotService snapshotService;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private EntityManager entityManager;

    private User user;
    private User stranger;
    private DiaryRoutine routine;
    private UUID itemA;
    private UUID itemB;

    @BeforeEach
    void seed() {
        user = newUser("focus-owner");
        stranger = newUser("focus-stranger");

        Habit first = newHabit(user, "Read");
        Habit second = newHabit(user, "Stretch");

        Schedule schedule = new Schedule();
        schedule.setDays(new HashSet<>(Arrays.asList(WeekDay.values())));
        schedule = scheduleRepository.saveAndFlush(schedule);

        routine = new DiaryRoutine();
        routine.setName("Morning");
        routine.setIconId("icon");
        routine.setUser(user);
        routine.setSchedule(schedule);
        routine.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

        RoutineSection section = new RoutineSection();
        section.setName("Wake");
        section.setIconId("icon");
        section.setStartTime(LocalTime.of(6, 0));
        section.setEndTime(LocalTime.of(8, 0));
        section.setOrderIndex(0);
        section.setFavorite(false);
        section.setRoutine(routine);

        HabitGroup groupA = group(first, section, 6);
        HabitGroup groupB = group(second, section, 7);
        section.setHabitGroups(List.of(groupA, groupB));
        section.setTaskGroups(new ArrayList<>());
        routine.setRoutineSections(List.of(section));

        routine = diaryRoutineRepository.saveAndFlush(routine);
        entityManager.flush();
        entityManager.clear();

        routine = diaryRoutineRepository.findById(routine.getId()).orElseThrow();
        user = userRepository.findById(user.getId()).orElseThrow();
        stranger = userRepository.findById(stranger.getId()).orElseThrow();
        List<HabitGroup> groups = routine.getRoutineSections().get(0).getHabitGroups();
        itemA = groups.get(0).getId();
        itemB = groups.get(1).getId();
    }

    // ---------------------------------------------------------------- cycles

    @Test
    void aCompletedCycleIsFiledUnderTheOwnersDay() {
        Instant start = Instant.parse("2026-08-28T10:00:00Z");
        FocusCycleResponseDTO saved = focusService.recordCycle(user,
            new RecordCycleRequestDTO(itemA, CycleKind.POMODORO, start, start.plusSeconds(25 * 60), 25));

        assertThat(saved.itemGroupId()).isEqualTo(itemA);
        assertThat(saved.kind()).isEqualTo(CycleKind.POMODORO);
        assertThat(saved.date()).isEqualTo(LocalDate.now());
    }

    @Test
    void aCycleMayRunOnNoItemAtAll() {
        Instant start = Instant.now().minusSeconds(300);
        FocusCycleResponseDTO saved = focusService.recordCycle(user,
            new RecordCycleRequestDTO(null, CycleKind.SHORT_BREAK, start, Instant.now(), 5));

        assertThat(saved.itemGroupId()).isNull();
    }

    @Test
    void aCycleThatEndsBeforeItStartsIsRefused() {
        Instant start = Instant.now();
        assertThatThrownBy(() -> focusService.recordCycle(user,
            new RecordCycleRequestDTO(itemA, CycleKind.POMODORO, start, start.minusSeconds(1), 25)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorKey())
            .isEqualTo(ErrorKey.INVALID_REQUEST);
    }

    @Test
    void aCycleOnSomebodyElsesItemIsRefused() {
        Instant start = Instant.now().minusSeconds(60);
        assertThatThrownBy(() -> focusService.recordCycle(stranger,
            new RecordCycleRequestDTO(itemA, CycleKind.POMODORO, start, Instant.now(), 1)))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorKey())
            .isEqualTo(ErrorKey.ROUTINE_NOT_OWNED);
    }

    // ----------------------------------------------------------- micro-tasks

    @Test
    void aMicroTaskBelongsToOneItem_andDoesNotFollowToAnother() {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Water", false));

        assertThat(focusService.listMicroTasks(user, itemA)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("Water");
        // The user's rule, first half: changing item does not carry the list over.
        assertThat(focusService.listMicroTasks(user, itemB)).isEmpty();
    }

    @Test
    void aPinnedMicroTaskIsCreatedOnEveryItemThePersonMovesTo() {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Stretch", true));

        // The user's rule, second half: pinned, it is CREATED and linked to the new item too.
        List<FocusMicroTaskResponseDTO> onB = focusService.listMicroTasks(user, itemB);
        assertThat(onB).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("Stretch");
            assertThat(t.itemGroupId()).isEqualTo(itemB);
            assertThat(t.pinned()).isTrue();
            // Its own row, independently tickable: not done because the one on A is not.
            assertThat(t.doneAt()).isNull();
        });

        // Both rows exist, one per item, and they are different rows.
        FocusMicroTaskResponseDTO onA = focusService.listMicroTasks(user, itemA).get(0);
        assertThat(onA.id()).isNotEqualTo(onB.get(0).id());
    }

    @Test
    void materialisingIsIdempotent_soAskingTwiceCreatesNothing() {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Stretch", true));

        focusService.listMicroTasks(user, itemB);
        focusService.listMicroTasks(user, itemB);
        focusService.listMicroTasks(user, itemB);

        assertThat(focusService.listMicroTasks(user, itemB)).hasSize(1);
    }

    @Test
    void addingTheSameNameTwiceOnOneItemReturnsTheExistingRow() {
        // What lets a client retry a request whose response it lost, instead of tripping the unique
        // constraint into a 500.
        FocusMicroTaskResponseDTO first = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, " Water ", false));
        FocusMicroTaskResponseDTO again = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Water", false));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(focusService.listMicroTasks(user, itemA)).hasSize(1);
    }

    @Test
    void pinningIsAPropertyOfTheName_notOfOneRow() {
        // Unpinned on A, then materialised nowhere.
        FocusMicroTaskResponseDTO onA = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Stretch", false));
        assertThat(focusService.listMicroTasks(user, itemB)).isEmpty();

        // Pin it on A: it now appears on B too.
        focusService.setPinned(user, onA.id(), true);
        List<FocusMicroTaskResponseDTO> onB = focusService.listMicroTasks(user, itemB);
        assertThat(onB).singleElement().extracting(FocusMicroTaskResponseDTO::pinned).isEqualTo(true);

        // Unpin it from B: the row on A stops being pinned as well. The answer to "is this kept?"
        // cannot depend on which item you happen to be looking at.
        focusService.setPinned(user, onB.get(0).id(), false);
        assertThat(focusService.listMicroTasks(user, itemA)).singleElement()
            .extracting(FocusMicroTaskResponseDTO::pinned).isEqualTo(false);
    }

    @Test
    void togglingTicksAndUnticks() {
        FocusMicroTaskResponseDTO task = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Water", false));

        assertThat(focusService.toggleMicroTask(user, task.id()).doneAt()).isNotNull();
        assertThat(focusService.toggleMicroTask(user, task.id()).doneAt()).isNull();
    }

    @Test
    void deletingRemovesTheRow() {
        FocusMicroTaskResponseDTO task = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Water", false));

        focusService.deleteMicroTask(user, task.id());

        assertThat(focusService.listMicroTasks(user, itemA)).isEmpty();
    }

    @Test
    void somebodyElseCannotReadWriteOrDeleteYourMicroTasks() {
        FocusMicroTaskResponseDTO task = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Water", false));

        assertThatThrownBy(() -> focusService.listMicroTasks(stranger, itemA)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> focusService.addMicroTask(stranger, new CreateMicroTaskRequestDTO(itemA, "Hijack", false)))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> focusService.toggleMicroTask(stranger, task.id())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> focusService.setPinned(stranger, task.id(), true)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> focusService.deleteMicroTask(stranger, task.id())).isInstanceOf(BusinessException.class);
    }

    // ------------------------------------------------------------------- day

    @Test
    void theDayViewReadsWithoutMaterialisingAnything() {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Stretch", true));
        Instant start = Instant.now().minusSeconds(1500);
        focusService.recordCycle(user, new RecordCycleRequestDTO(itemA, CycleKind.POMODORO, start, Instant.now(), 25));

        FocusDayResponseDTO day = focusService.getDay(user, LocalDate.now());

        assertThat(day.cycles()).hasSize(1);
        // Only the row on A: the history view must not invent a row on B for an item the person
        // never arrived at.
        assertThat(day.microTasks()).singleElement().extracting(FocusMicroTaskResponseDTO::itemGroupId).isEqualTo(itemA);
    }

    // --------------------------------------------------------------- order

    @Test
    void aNewMicroTaskLandsAtTheEndOfTheItemsList() {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "First", false));
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Second", false));
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Third", false));

        assertThat(focusService.listMicroTasks(user, itemA)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("First", "Second", "Third");
    }

    @Test
    void reorderingRewritesTheListAndSurvivesTheNextRead() {
        UUID first = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "First", false)).id();
        UUID second = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Second", false)).id();
        UUID third = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Third", false)).id();

        focusService.reorderMicroTasks(user, new ReorderMicroTasksRequestDTO(itemA, List.of(third, first, second)));

        // Read back through the ordinary list path, not the reorder's own return value: the point
        // is that the order was WRITTEN, not that the method can sort a list in memory.
        assertThat(focusService.listMicroTasks(user, itemA)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("Third", "First", "Second");
    }

    @Test
    void aRowThePayloadNeverMentionsKeepsItsPlaceAtTheEnd() {
        // The list grew in another tab between the read and the drop. Losing that row, or refusing
        // the whole drag over it, are both worse than putting it last.
        UUID first = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "First", false)).id();
        UUID second = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Second", false)).id();
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Latecomer", false));

        focusService.reorderMicroTasks(user, new ReorderMicroTasksRequestDTO(itemA, List.of(second, first)));

        assertThat(focusService.listMicroTasks(user, itemA)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("Second", "First", "Latecomer");
    }

    @Test
    void anIdFromAnotherItemIsIgnoredRatherThanMoved() {
        UUID onA = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Water", false)).id();
        UUID onB = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemB, "Elsewhere", false)).id();

        focusService.reorderMicroTasks(user, new ReorderMicroTasksRequestDTO(itemA, List.of(onB, onA)));

        assertThat(focusService.listMicroTasks(user, itemA)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("Water");
        assertThat(focusService.listMicroTasks(user, itemB)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("Elsewhere");
    }

    @Test
    void oneItemsOrderSaysNothingAboutAnothers() {
        UUID a1 = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "A one", false)).id();
        UUID a2 = focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "A two", false)).id();
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemB, "B one", false));
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemB, "B two", false));

        focusService.reorderMicroTasks(user, new ReorderMicroTasksRequestDTO(itemA, List.of(a2, a1)));

        assertThat(focusService.listMicroTasks(user, itemB)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("B one", "B two");
    }

    @Test
    void aPinnedNameMaterialisedOnAnotherItemLandsAtTheEndThere() {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemB, "Already here", false));
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Stretch", true));

        assertThat(focusService.listMicroTasks(user, itemB)).extracting(FocusMicroTaskResponseDTO::name)
            .containsExactly("Already here", "Stretch");
    }

    @Test
    void reorderingSomebodyElsesItemIsRefused() {
        assertThatThrownBy(() -> focusService.reorderMicroTasks(
                stranger, new ReorderMicroTasksRequestDTO(itemA, List.of(UUID.randomUUID()))))
            .isInstanceOf(BusinessException.class);
    }

    // -------------------------------------------------------------- snapshot

    @Test
    void theSnapshotShowsEachItemsMicroTasksAndPomodoros_onItsOwnCheckRow() {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Water", false));
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemA, "Stretch", false));
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(itemB, "Breathe", false));
        Instant start = Instant.now().minusSeconds(1500);
        focusService.recordCycle(user, new RecordCycleRequestDTO(itemA, CycleKind.POMODORO, start, Instant.now(), 25));
        focusService.recordCycle(user, new RecordCycleRequestDTO(itemA, CycleKind.SHORT_BREAK, start, Instant.now(), 5));
        focusService.recordCycle(user, new RecordCycleRequestDTO(null, CycleKind.POMODORO, start, Instant.now(), 25));

        RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, user, LocalDate.now());
        SnapshotResponseDTO dto = snapshotService.toResponseDTO(snapshot);

        SnapshotCheckResponseDTO checkA = dto.checks().stream().filter(c -> itemA.equals(c.originalGroupId())).findFirst().orElseThrow();
        SnapshotCheckResponseDTO checkB = dto.checks().stream().filter(c -> itemB.equals(c.originalGroupId())).findFirst().orElseThrow();

        // "Each micro-task created for a task or habit of the routine", on that habit's row.
        assertThat(checkA.microTasks()).extracting(FocusMicroTaskResponseDTO::name).containsExactly("Water", "Stretch");
        assertThat(checkB.microTasks()).extracting(FocusMicroTaskResponseDTO::name).containsExactly("Breathe");

        // Only POMODORO cycles count as pomodoros; the break on A does not.
        assertThat(checkA.pomodoros()).isEqualTo(1);
        assertThat(checkB.pomodoros()).isZero();

        // All three cycles of the day appear: two on this routine's item, one on no item at all.
        assertThat(dto.focusCycles()).hasSize(3);
    }

    @Test
    void anotherRoutinesCyclesStayOutOfThisRoutinesSnapshot() {
        // The javadoc on toResponseDTO promises it; until now no test had a second routine to
        // prove it, so half the filter ran against nothing.
        UUID elsewhere = newRoutineWithOneHabit(user, "Evening", 20);
        Instant start = Instant.now().minusSeconds(1500);
        focusService.recordCycle(user, new RecordCycleRequestDTO(itemA, CycleKind.POMODORO, start, Instant.now(), 25));
        focusService.recordCycle(user, new RecordCycleRequestDTO(elsewhere, CycleKind.POMODORO, start, Instant.now(), 25));
        focusService.recordCycle(user, new RecordCycleRequestDTO(null, CycleKind.POMODORO, start, Instant.now(), 25));

        SnapshotResponseDTO morning = snapshotService.toResponseDTO(
            snapshotService.createSnapshot(routine, user, LocalDate.now()));

        // This routine's own item, plus the cycle on no item, which has nowhere else to appear.
        // The Evening routine's pomodoro is left to the Evening routine's snapshot.
        assertThat(morning.focusCycles()).extracting(FocusCycleResponseDTO::itemGroupId)
            .containsExactlyInAnyOrder(itemA, null);
    }

    @Test
    void theDaysSnapshotsShareOneReadOfTheFocusRows() {
        // Two routines snapshotted on the same day come back with the same focus rows each, from a
        // single read per table rather than one per routine. The behaviour is asserted; the query
        // count is what the hoist in getSnapshotsForDay is for.
        UUID elsewhere = newRoutineWithOneHabit(user, "Evening", 20);
        Instant start = Instant.now().minusSeconds(1500);
        focusService.recordCycle(user, new RecordCycleRequestDTO(null, CycleKind.POMODORO, start, Instant.now(), 25));
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(elsewhere, "Tea", false));
        snapshotService.createSnapshot(routine, user, LocalDate.now());
        snapshotService.createSnapshot(eveningRoutine, user, LocalDate.now());

        List<SnapshotResponseDTO> day = snapshotService.getSnapshotsForDay(LocalDate.now(), user.getId());

        assertThat(day).hasSize(2);
        assertThat(day).allSatisfy(dto -> assertThat(dto.focusCycles()).hasSize(1));
        SnapshotResponseDTO evening = day.stream().filter(d -> d.routineName().equals("Evening")).findFirst().orElseThrow();
        assertThat(evening.checks()).flatExtracting(SnapshotCheckResponseDTO::microTasks)
            .extracting(FocusMicroTaskResponseDTO::name).containsExactly("Tea");
    }

    // -------------------------------------------------------------- helpers

    private DiaryRoutine eveningRoutine;

    /** A second scheduled routine with one habit, so cross-routine rules have something to cross. */
    private UUID newRoutineWithOneHabit(User owner, String name, int hour) {
        Habit habit = newHabit(owner, name + " habit");
        Schedule schedule = new Schedule();
        schedule.setDays(new HashSet<>(Arrays.asList(WeekDay.values())));
        schedule = scheduleRepository.saveAndFlush(schedule);

        DiaryRoutine other = new DiaryRoutine();
        other.setName(name);
        other.setIconId("icon");
        other.setUser(owner);
        other.setSchedule(schedule);
        other.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

        RoutineSection section = new RoutineSection();
        section.setName(name);
        section.setIconId("icon");
        section.setStartTime(LocalTime.of(hour, 0));
        section.setEndTime(LocalTime.of(hour + 2, 0));
        section.setOrderIndex(0);
        section.setFavorite(false);
        section.setRoutine(other);
        section.setHabitGroups(List.of(group(habit, section, hour)));
        section.setTaskGroups(new ArrayList<>());
        other.setRoutineSections(List.of(section));

        other = diaryRoutineRepository.saveAndFlush(other);
        entityManager.flush();
        entityManager.clear();
        eveningRoutine = diaryRoutineRepository.findById(other.getId()).orElseThrow();
        user = userRepository.findById(user.getId()).orElseThrow();
        routine = diaryRoutineRepository.findById(routine.getId()).orElseThrow();
        return eveningRoutine.getRoutineSections().get(0).getHabitGroups().get(0).getId();
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

    private Habit newHabit(User owner, String name) {
        Habit h = new Habit();
        h.setName(name);
        h.setIconId("icon");
        h.setImportance(3);
        h.setDificulty(2);
        h.setDescription(name);
        h.setMotivationalPhrase("Go");
        h.setCategories(new ArrayList<>());
        h.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        h.setUser(owner);
        return habitRepository.saveAndFlush(h);
    }

    private static HabitGroup group(Habit habit, RoutineSection section, int hour) {
        HabitGroup g = new HabitGroup();
        g.setHabit(habit);
        g.setRoutineSection(section);
        g.setStartTime(LocalTime.of(hour, 0));
        g.setEndTime(LocalTime.of(hour, 30));
        g.setHabitGroupChecks(new ArrayList<>());
        return g;
    }
}
