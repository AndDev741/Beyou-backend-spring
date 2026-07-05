package beyou.beyouapp.backend.integration.offline;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the offline-sync wire contract: every create endpoint accepts an
 * optional client-supplied UUID. When present, the persisted entity keeps
 * that id, and replaying the same create (lost-response retry from an
 * offline mobile client) must not duplicate the row.
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
        user = new User();
        user.setName("Offline Sync IT User");
        user.setEmail("offline-sync-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        // XpByLevel rows are seeded by Flyway's R__seed_xp_by_level.sql (all levels).
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createCategoryKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        CategoryRequestDTO dto = new CategoryRequestDTO(clientId, "Offline Cat", "icon-1", "desc", ExperienceLevel.BEGINNER);

        categoryService.createCategoryEntity(dto, user);
        assertTrue(categoryRepository.findById(clientId).isPresent());

        categoryService.createCategoryEntity(dto, user); // replay — lost-response retry
        assertEquals(1, categoryRepository.findAllByUserId(user.getId()).orElseThrow().stream()
                .filter(c -> c.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createHabitKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        CreateHabitDTO dto = new CreateHabitDTO(clientId, "Offline Habit", null, null, "icon-2", 3, 3,
                List.of(), ExperienceLevel.BEGINNER);

        habitService.createHabitEntity(dto, user.getId());
        assertTrue(habitRepository.findById(clientId).isPresent());

        habitService.createHabitEntity(dto, user.getId()); // replay — lost-response retry
        assertEquals(1, habitRepository.findAllByUserId(user.getId()).stream()
                .filter(h -> h.getId().equals(clientId)).count());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void createTaskKeepsClientSuppliedIdAndReplayIsIdempotent() {
        UUID clientId = UUID.randomUUID();
        CreateTaskRequestDTO dto = new CreateTaskRequestDTO(clientId, "Offline Task", null, "icon-3", 2, 2,
                List.of(), false);

        taskService.createTaskEntity(dto, user.getId());
        assertTrue(taskRepository.findById(clientId).isPresent());

        taskService.createTaskEntity(dto, user.getId()); // replay — lost-response retry
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
}
