package beyou.beyouapp.backend.integration.offline;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the offline-sync wire contract: every create endpoint accepts an
 * optional client-supplied UUID. When present, the persisted entity keeps
 * that id, and replaying the same create (lost-response retry from an
 * offline mobile client) must not duplicate the row.
 *
 * Also locks in the cross-user ownership guard: a client-supplied id that
 * already belongs to another user's row must never be hijacked/overwritten
 * by a create call from a different user — the merge() behind a
 * client-supplied id does a GLOBAL select, so this check is the only thing
 * standing between "offline replay" and an IDOR.
 *
 * All five entities go through the PUBLIC service create methods here (not
 * the internal *Entity helpers) so the tests exercise the same path the
 * controllers use.
 */
class ClientSuppliedIdIT extends AbstractIntegrationTest {

    @Autowired private CategoryService categoryService;
    @Autowired private CategoryRepository categoryRepository;

    @Autowired private HabitService habitService;
    @Autowired private HabitRepository habitRepository;

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepository;

    @Autowired private GoalService goalService;
    @Autowired private GoalRepository goalRepository;

    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;

    @Autowired private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = createUser();
        // XpByLevel rows are seeded by Flyway's R__seed_xp_by_level.sql (all levels).
    }

    private User createUser() {
        User newUser = new User();
        newUser.setName("Offline Sync IT User");
        newUser.setEmail("offline-sync-" + UUID.randomUUID() + "@test.com");
        newUser.setPassword("password123");
        return userRepository.saveAndFlush(newUser);
    }

    // ---------------------------------------------------------------------
    // Idempotent replay (same user) — all five, through the public methods
    // ---------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createCategoryKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        CategoryRequestDTO dto = new CategoryRequestDTO(clientId, "Offline Cat", "icon-1", "desc", ExperienceLevel.BEGINNER);

        categoryService.createCategory(dto, user.getId());
        assertTrue(categoryRepository.findById(clientId).isPresent());

        categoryService.createCategory(dto, user.getId()); // replay — lost-response retry
        assertEquals(1, categoryRepository.findAllByUserId(user.getId()).orElseThrow().stream()
                .filter(c -> c.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createHabitKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        CreateHabitDTO dto = new CreateHabitDTO(clientId, "Offline Habit", null, null, "icon-2", 3, 3,
                List.of(), ExperienceLevel.BEGINNER);

        habitService.createHabit(dto, user.getId());
        assertTrue(habitRepository.findById(clientId).isPresent());

        habitService.createHabit(dto, user.getId()); // replay — lost-response retry
        assertEquals(1, habitRepository.findAllByUserId(user.getId()).stream()
                .filter(h -> h.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createTaskKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        CreateTaskRequestDTO dto = new CreateTaskRequestDTO(clientId, "Offline Task", null, "icon-3", 2, 2,
                List.of(), false);

        taskService.createTask(dto, user.getId());
        assertTrue(taskRepository.findById(clientId).isPresent());

        taskService.createTask(dto, user.getId()); // replay — lost-response retry
        assertEquals(1, taskRepository.findAllByUserId(user.getId()).orElseThrow().stream()
                .filter(t -> t.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createGoalKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        CreateGoalRequestDTO dto = new CreateGoalRequestDTO(clientId, "Offline Goal", null, "icon-4", 10.0, "reps",
                0.0, List.of(), null, LocalDate.now(), LocalDate.now().plusDays(30),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);

        goalService.createGoal(dto, user);
        assertTrue(goalRepository.findById(clientId).isPresent());

        goalService.createGoal(dto, user); // replay — lost-response retry
        assertEquals(1, goalRepository.findAllByUserId(user.getId()).orElseThrow().stream()
                .filter(g -> g.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createRoutineKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        RoutineSectionRequestDTO section = new RoutineSectionRequestDTO(null, "Morning", "icon", LocalTime.of(6, 0),
                null, null, null, null);
        DiaryRoutineRequestDTO dto = new DiaryRoutineRequestDTO(clientId, "Offline Routine", "icon-5", List.of(section));

        diaryRoutineService.createDiaryRoutine(dto, user);
        assertTrue(diaryRoutineRepository.findById(clientId).isPresent());

        diaryRoutineService.createDiaryRoutine(dto, user); // replay — lost-response retry
        assertEquals(1, diaryRoutineRepository.findAllByUserId(user.getId()).stream()
                .filter(r -> r.getId().equals(clientId)).count());
    }

    // ---------------------------------------------------------------------
    // Cross-user IDOR guard: user B create-replaying user A's id must be
    // rejected with *_NOT_OWNED, and A's row must be left untouched.
    // ---------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondUserCannotHijackAnotherUsersCategoryViaClientSuppliedId() {
        UUID clientId = UUID.randomUUID();
        CategoryRequestDTO originalDto = new CategoryRequestDTO(clientId, "Owner Cat", "icon-1", "desc", ExperienceLevel.BEGINNER);
        categoryService.createCategory(originalDto, user.getId());

        User attacker = createUser();
        CategoryRequestDTO hijackDto = new CategoryRequestDTO(clientId, "Hijacked Cat", "icon-evil", "evil", ExperienceLevel.BEGINNER);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> categoryService.createCategory(hijackDto, attacker.getId()));
        assertEquals(ErrorKey.CATEGORY_NOT_OWNED, exception.getErrorKey());

        Category unchanged = categoryRepository.findById(clientId).orElseThrow();
        assertEquals("Owner Cat", unchanged.getName());
        assertEquals(user.getId(), unchanged.getUser().getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondUserCannotHijackAnotherUsersHabitViaClientSuppliedId() {
        UUID clientId = UUID.randomUUID();
        CreateHabitDTO originalDto = new CreateHabitDTO(clientId, "Owner Habit", null, null, "icon-2", 3, 3,
                List.of(), ExperienceLevel.BEGINNER);
        habitService.createHabit(originalDto, user.getId());

        User attacker = createUser();
        CreateHabitDTO hijackDto = new CreateHabitDTO(clientId, "Hijacked Habit", null, null, "icon-evil", 1, 1,
                List.of(), ExperienceLevel.BEGINNER);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> habitService.createHabit(hijackDto, attacker.getId()));
        assertEquals(ErrorKey.HABIT_NOT_OWNED, exception.getErrorKey());

        Habit unchanged = habitRepository.findById(clientId).orElseThrow();
        assertEquals("Owner Habit", unchanged.getName());
        assertEquals(user.getId(), unchanged.getUser().getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondUserCannotHijackAnotherUsersTaskViaClientSuppliedId() {
        UUID clientId = UUID.randomUUID();
        CreateTaskRequestDTO originalDto = new CreateTaskRequestDTO(clientId, "Owner Task", null, "icon-3", 2, 2,
                List.of(), false);
        taskService.createTask(originalDto, user.getId());

        User attacker = createUser();
        CreateTaskRequestDTO hijackDto = new CreateTaskRequestDTO(clientId, "Hijacked Task", null, "icon-evil", 1, 1,
                List.of(), false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> taskService.createTask(hijackDto, attacker.getId()));
        assertEquals(ErrorKey.TASK_NOT_OWNED, exception.getErrorKey());

        Task unchanged = taskRepository.findById(clientId).orElseThrow();
        assertEquals("Owner Task", unchanged.getName());
        assertEquals(user.getId(), unchanged.getUser().getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondUserCannotHijackAnotherUsersGoalViaClientSuppliedId() {
        UUID clientId = UUID.randomUUID();
        CreateGoalRequestDTO originalDto = new CreateGoalRequestDTO(clientId, "Owner Goal", null, "icon-4", 10.0, "reps",
                0.0, List.of(), null, LocalDate.now(), LocalDate.now().plusDays(30),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);
        goalService.createGoal(originalDto, user);

        User attacker = createUser();
        CreateGoalRequestDTO hijackDto = new CreateGoalRequestDTO(clientId, "Hijacked Goal", null, "icon-evil", 1.0, "reps",
                0.0, List.of(), null, LocalDate.now(), LocalDate.now().plusDays(30),
                GoalStatus.NOT_STARTED, GoalTerm.SHORT_TERM);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> goalService.createGoal(hijackDto, attacker));
        assertEquals(ErrorKey.GOAL_NOT_OWNED, exception.getErrorKey());

        var unchanged = goalRepository.findById(clientId).orElseThrow();
        assertEquals("Owner Goal", unchanged.getName());
        assertEquals(user.getId(), unchanged.getUser().getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondUserCannotHijackAnotherUsersRoutineViaClientSuppliedId() {
        UUID clientId = UUID.randomUUID();
        RoutineSectionRequestDTO section = new RoutineSectionRequestDTO(null, "Morning", "icon", LocalTime.of(6, 0),
                null, null, null, null);
        DiaryRoutineRequestDTO originalDto = new DiaryRoutineRequestDTO(clientId, "Owner Routine", "icon-5", List.of(section));
        diaryRoutineService.createDiaryRoutine(originalDto, user);

        User attacker = createUser();
        RoutineSectionRequestDTO hijackSection = new RoutineSectionRequestDTO(null, "Evil", "icon-evil", LocalTime.of(7, 0),
                null, null, null, null);
        DiaryRoutineRequestDTO hijackDto = new DiaryRoutineRequestDTO(clientId, "Hijacked Routine", "icon-evil", List.of(hijackSection));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> diaryRoutineService.createDiaryRoutine(hijackDto, attacker));
        assertEquals(ErrorKey.ROUTINE_NOT_OWNED, exception.getErrorKey());

        DiaryRoutine unchanged = diaryRoutineRepository.findById(clientId).orElseThrow();
        assertEquals("Owner Routine", unchanged.getName());
        assertEquals(user.getId(), unchanged.getUser().getId());
    }

    // ---------------------------------------------------------------------
    // Replay preserves createdAt (Finding 2 — solved by Finding 1's design:
    // returning the existing row untouched means no save() call at all).
    // ---------------------------------------------------------------------

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void replayingCategoryCreatePreservesCreatedAt() {
        UUID clientId = UUID.randomUUID();
        CategoryRequestDTO dto = new CategoryRequestDTO(clientId, "Offline Cat", "icon-1", "desc", ExperienceLevel.BEGINNER);

        categoryService.createCategory(dto, user.getId());
        Date createdAt = categoryRepository.findById(clientId).orElseThrow().getCreatedAt();

        categoryService.createCategory(dto, user.getId()); // replay — lost-response retry

        Category afterReplay = categoryRepository.findById(clientId).orElseThrow();
        assertEquals(createdAt, afterReplay.getCreatedAt());
        assertEquals("Offline Cat", afterReplay.getName());
        assertEquals(1, categoryRepository.findAllByUserId(user.getId()).orElseThrow().stream()
                .filter(c -> c.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void replayingHabitCreatePreservesCreatedAt() {
        UUID clientId = UUID.randomUUID();
        CreateHabitDTO dto = new CreateHabitDTO(clientId, "Offline Habit", null, null, "icon-2", 3, 3,
                List.of(), ExperienceLevel.BEGINNER);

        habitService.createHabit(dto, user.getId());
        Date createdAt = habitRepository.findById(clientId).orElseThrow().getCreatedAt();

        habitService.createHabit(dto, user.getId()); // replay — lost-response retry

        Habit afterReplay = habitRepository.findById(clientId).orElseThrow();
        assertEquals(createdAt, afterReplay.getCreatedAt());
        assertEquals("Offline Habit", afterReplay.getName());
        assertEquals(1, habitRepository.findAllByUserId(user.getId()).stream()
                .filter(h -> h.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void replayingTaskCreatePreservesCreatedAt() {
        UUID clientId = UUID.randomUUID();
        CreateTaskRequestDTO dto = new CreateTaskRequestDTO(clientId, "Offline Task", null, "icon-3", 2, 2,
                List.of(), false);

        taskService.createTask(dto, user.getId());
        Date createdAt = taskRepository.findById(clientId).orElseThrow().getCreatedAt();

        taskService.createTask(dto, user.getId()); // replay — lost-response retry

        Task afterReplay = taskRepository.findById(clientId).orElseThrow();
        assertEquals(createdAt, afterReplay.getCreatedAt());
        assertEquals("Offline Task", afterReplay.getName());
        assertEquals(1, taskRepository.findAllByUserId(user.getId()).orElseThrow().stream()
                .filter(t -> t.getId().equals(clientId)).count());
    }
}
