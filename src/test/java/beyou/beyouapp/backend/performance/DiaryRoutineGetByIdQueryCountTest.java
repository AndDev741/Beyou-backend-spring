package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.TaskGroup;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates and prevents the deeply nested N+1 in
 * {@code DiaryRoutineService.getDiaryRoutineById()}.
 *
 * <p>The read path traverses 4 levels of lazy associations:
 * <pre>
 * DiaryRoutine
 *   └─ routineSections (lazy List)
 *      └─ taskGroups (lazy List), habitGroups (lazy List)
 *         └─ task / habit (lazy ManyToOne)
 *         └─ taskGroupChecks / habitGroupChecks (lazy List)
 * </pre>
 *
 * <p>Without batching, fetching a routine with S sections × G groups produces
 * statements proportional to S*G. With {@code @BatchSize} on each association,
 * the count collapses to a small constant regardless of S or G.
 */
@Transactional
class DiaryRoutineGetByIdQueryCountTest extends AbstractIntegrationTest {

    private static final int SECTION_COUNT = 4;
    private static final int GROUPS_PER_SECTION = 3;

    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HabitRepository habitRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private DiaryRoutineRepository routineRepository;

    @Autowired
    private DiaryRoutineService routineService;

    @Test
    @DisplayName("getDiaryRoutineById should fetch a 4-level deep tree in a bounded number of queries")
    void getDiaryRoutineById_isBoundedRegardlessOfTreeSize() {
        // Arrange — seed user, habits/tasks, routine with sections+groups
        User user = seedUser();

        // Pre-create habits and tasks so each group can reference one
        List<Habit> habits = new ArrayList<>();
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < SECTION_COUNT * GROUPS_PER_SECTION; i++) {
            habits.add(seedHabit(user, "habit-" + i));
            tasks.add(seedTask(user, "task-" + i));
        }

        DiaryRoutine routine = new DiaryRoutine();
        routine.setName("Perf test routine");
        routine.setIconId("icon");
        routine.setUser(user);

        int habitIdx = 0;
        int taskIdx = 0;
        for (int s = 0; s < SECTION_COUNT; s++) {
            RoutineSection section = new RoutineSection();
            section.setName("section-" + s);
            section.setIconId("icon");
            section.setStartTime(LocalTime.of(6 + s, 0));
            section.setEndTime(LocalTime.of(7 + s, 0));
            section.setOrderIndex(s);
            section.setRoutine(routine);

            List<HabitGroup> habitGroups = new ArrayList<>();
            List<TaskGroup> taskGroups = new ArrayList<>();
            for (int g = 0; g < GROUPS_PER_SECTION; g++) {
                HabitGroup hg = new HabitGroup();
                hg.setHabit(habits.get(habitIdx++));
                hg.setRoutineSection(section);
                hg.setStartTime(LocalTime.of(6 + s, 10 * g));
                hg.setEndTime(LocalTime.of(6 + s, 10 * g + 5));
                hg.setHabitGroupChecks(new ArrayList<>());
                habitGroups.add(hg);

                TaskGroup tg = new TaskGroup();
                tg.setTask(tasks.get(taskIdx++));
                tg.setRoutineSection(section);
                tg.setStartTime(LocalTime.of(6 + s, 10 * g + 30));
                tg.setEndTime(LocalTime.of(6 + s, 10 * g + 35));
                tg.setTaskGroupChecks(new ArrayList<>());
                taskGroups.add(tg);
            }
            section.setHabitGroups(habitGroups);
            section.setTaskGroups(taskGroups);
            routine.getRoutineSections().add(section);
        }

        DiaryRoutine saved = routineRepository.saveAndFlush(routine);
        em.flush();
        em.clear();

        var stats = new HibernateStatistics(emf);

        // Act
        var result = routineService.getDiaryRoutineById(saved.getId(), user.getId());

        // Assert correctness
        assertThat(result.routineSections()).hasSize(SECTION_COUNT);
        assertThat(result.routineSections().get(0).habitGroup()).hasSize(GROUPS_PER_SECTION);
        assertThat(result.routineSections().get(0).taskGroup()).hasSize(GROUPS_PER_SECTION);

        // Assert performance.
        // Without @BatchSize: 1 (routine) + 1 (sections) + S (one taskGroups/section)
        //                   + S (one habitGroups/section) = 2 + 2*S statements,
        //                   scaling linearly with section count.
        // With @BatchSize on taskGroups/habitGroups: 1 + 1 + 1 + 1 = 4 statements,
        //   constant regardless of section count.
        // Threshold of 6 catches any per-section growth.
        int unfixedEstimate = 2 + 2 * SECTION_COUNT;
        assertThat(stats.statementCount())
                .as("Should be bounded (≤6) — current N+1 would mean ~%d statements for %d sections. Stats: %s",
                        unfixedEstimate, SECTION_COUNT, stats)
                .isLessThanOrEqualTo(6);

        System.out.println("[N+1 fix] DiaryRoutineService.getDiaryRoutineById with "
                + SECTION_COUNT + " sections × " + GROUPS_PER_SECTION + " groups → " + stats);
    }

    // --- seed helpers ---

    private User seedUser() {
        User user = new User();
        user.setName("Routine Query Tester");
        user.setEmail("routine-query-test@example.com");
        user.setPassword("placeholder");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        return userRepository.saveAndFlush(user);
    }

    private Habit seedHabit(User user, String name) {
        Habit h = new Habit();
        h.setName(name);
        h.setDescription("seed");
        h.setIconId("icon");
        h.setImportance(1);
        h.setDificulty(1);
        h.setUser(user);
        h.setCategories(new ArrayList<>());
        return habitRepository.saveAndFlush(h);
    }

    private Task seedTask(User user, String name) {
        Task t = new Task();
        t.setName(name);
        t.setDescription("seed");
        t.setIconId("icon");
        t.setImportance(1);
        t.setDificulty(1);
        t.setUser(user);
        t.setCategories(new ArrayList<>());
        return taskRepository.saveAndFlush(t);
    }
}
