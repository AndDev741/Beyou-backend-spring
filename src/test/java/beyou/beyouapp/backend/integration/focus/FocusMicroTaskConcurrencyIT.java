package beyou.beyouapp.backend.integration.focus;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.focus.FocusMicroTask;
import beyou.beyouapp.backend.domain.focus.FocusMicroTaskRepository;
import beyou.beyouapp.backend.domain.focus.FocusService;
import beyou.beyouapp.backend.domain.focus.dto.CreateMicroTaskRequestDTO;
import beyou.beyouapp.backend.domain.focus.dto.FocusMicroTaskResponseDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

/**
 * Two writes to one item's micro-task list arriving at the same moment.
 *
 * <p>Not hypothetical: the web input fires on Enter and again on blur, the mobile one on Done and
 * again on blur, and prod logged both halves of the pair 6 ms apart, one 201 and one 409 off
 * {@code focus_micro_tasks_unique_per_item}. The service already treated a repeated name as the same
 * row; it just could not see a row the other transaction had not committed yet.
 *
 * <p>Committed rows and real threads, which is why this is not a case in {@link FocusServiceIT}:
 * that class is {@code @Transactional}, so everything in it shares one transaction and a race
 * cannot happen. Several rounds, because one pair of threads can miss each other by luck; across
 * the rounds, the unlocked code loses at least one of them every time it has been run.
 */
class FocusMicroTaskConcurrencyIT extends AbstractIntegrationTest {

    private static final int ROUNDS = 12;

    @Autowired private FocusService focusService;
    @Autowired private FocusMicroTaskRepository microTaskRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private HabitRepository habitRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user;
    private List<UUID> items;

    @BeforeEach
    void seed() {
        user = newUser();

        Schedule schedule = new Schedule();
        schedule.setDays(new HashSet<>(Arrays.asList(WeekDay.values())));
        schedule = scheduleRepository.saveAndFlush(schedule);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setName("Race");
        routine.setIconId("icon");
        routine.setUser(user);
        routine.setSchedule(schedule);
        routine.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

        RoutineSection section = new RoutineSection();
        section.setName("All day");
        section.setIconId("icon");
        section.setStartTime(LocalTime.of(0, 0));
        section.setEndTime(LocalTime.of(23, 0));
        section.setOrderIndex(0);
        section.setFavorite(false);
        section.setRoutine(routine);

        // One item per round of the materialise race, plus the one that holds the pinned template.
        List<HabitGroup> groups = new ArrayList<>();
        for (int i = 0; i <= ROUNDS; i++) {
            HabitGroup g = new HabitGroup();
            g.setHabit(newHabit("Habit " + i));
            g.setRoutineSection(section);
            g.setStartTime(LocalTime.of(i % 23, 0));
            g.setEndTime(LocalTime.of(i % 23, 30));
            g.setHabitGroupChecks(new ArrayList<>());
            groups.add(g);
        }
        section.setHabitGroups(groups);
        section.setTaskGroups(new ArrayList<>());
        routine.setRoutineSections(List.of(section));
        UUID routineId = diaryRoutineRepository.saveAndFlush(routine).getId();

        items = transactionTemplate.execute(status -> diaryRoutineRepository.findById(routineId).orElseThrow()
            .getRoutineSections().get(0).getHabitGroups().stream()
            .map(HabitGroup::getId)
            .toList());
    }

    @AfterEach
    void tearDown() {
        // Micro-tasks go with the user (V27, ON DELETE CASCADE); the routine and habits go with the
        // user's JPA cascade.
        transactionTemplate.executeWithoutResult(status ->
            userRepository.findById(user.getId()).ifPresent(userService::deleteUser));
    }

    @Test
    @DisplayName("the same micro-task added twice at once is one row, and both requests get it")
    void aDoubleSubmitLandsOnOneRow() throws Exception {
        UUID item = items.get(0);
        for (int round = 0; round < ROUNDS; round++) {
            String name = "Step " + round;
            CreateMicroTaskRequestDTO request = new CreateMicroTaskRequestDTO(item, name, false);

            List<FocusMicroTaskResponseDTO> answers = race(() -> focusService.addMicroTask(user, request));

            assertThat(answers.get(0).id())
                .as("round %d: both requests describe the same row", round)
                .isEqualTo(answers.get(1).id());
            assertThat(rowsNamed(item, name))
                .as("round %d: the list holds %s once", round, name)
                .hasSize(1);
        }
    }

    @Test
    @DisplayName("two reads materialising the same pinned template at once write it once")
    void aPinnedTemplateMaterialisesOnceUnderConcurrentReads() throws Exception {
        focusService.addMicroTask(user, new CreateMicroTaskRequestDTO(items.get(0), "Water", true));

        // Every item past the first starts without the template, so each round is a fresh race.
        for (int round = 1; round <= ROUNDS; round++) {
            UUID item = items.get(round);

            List<List<FocusMicroTaskResponseDTO>> answers = race(() -> focusService.listMicroTasks(user, item));

            assertThat(answers).allSatisfy(list ->
                assertThat(list).extracting(FocusMicroTaskResponseDTO::name).containsExactly("Water"));
            assertThat(rowsNamed(item, "Water"))
                .as("round %d: materialised once", round)
                .hasSize(1);
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Runs the call on two threads released together and returns both results. A thread that
     * throws fails the test with that exception, so a unique-constraint violation shows up as
     * itself rather than as a missing row.
     */
    private <T> List<T> race(Callable<T> call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Throwable e) {
                        failures.add(e);
                        throw e;
                    }
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                try {
                    results.add(f.get(30, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException ignored) {
                    // Reported through `failures` below, with the original exception.
                }
            }
            assertThat(failures)
                .as("neither of two concurrent writes may fail; the second must see the first's row")
                .isEmpty();
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<FocusMicroTask> rowsNamed(UUID item, String name) {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        return microTaskRepository.findForItem(user.getId(), today, item).stream()
            .filter(t -> t.getName().equals(name))
            .toList();
    }

    private User newUser() {
        User u = new User();
        u.setName("focus-race");
        u.setEmail("focus-race-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone("UTC");
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }

    private Habit newHabit(String name) {
        Habit h = new Habit();
        h.setName(name);
        h.setIconId("icon");
        h.setImportance(3);
        h.setDificulty(2);
        h.setDescription(name);
        h.setMotivationalPhrase("Go");
        h.setCategories(new ArrayList<>());
        h.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        h.setUser(user);
        return habitRepository.saveAndFlush(h);
    }
}
