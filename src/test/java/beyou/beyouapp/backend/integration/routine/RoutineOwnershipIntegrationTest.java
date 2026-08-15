package beyou.beyouapp.backend.integration.routine;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whose habit is allowed into whose routine.
 *
 * <p>From the security audit of 2026-07-19, rated medium and authenticated: the create
 * flow resolved the client-supplied {@code habitId} and {@code taskId} with a bare
 * {@code findById} and no owner check, while the merge path beside it already went
 * through owner-checked lookups. So one account could embed another's habit in its own
 * routine — and then checking that routine incremented the victim's streak, moved their
 * category XP, and handed their habit and category back in the response.
 *
 * <p>Two accounts, because one account cannot demonstrate this. The victim's ids are
 * fetched the way an attacker would have to obtain them: from somewhere else entirely.
 */
class RoutineOwnershipIntegrationTest extends AbstractIntegrationTest {

    private static final String ATTACKER = "routine-owner-attacker@beyou.test";
    private static final String VICTIM = "routine-owner-victim@beyou.test";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CategoryService categoryService;
    @Autowired HabitService habitService;
    @Autowired TaskService taskService;
    @Autowired DiaryRoutineService diaryRoutineService;

    private User attacker;
    private User victim;

    @BeforeEach
    void setUp() {
        attacker = freshUser(ATTACKER, "someone with a routine");
        victim = freshUser(VICTIM, "someone with habits");
    }

    @Test
    @DisplayName("a routine cannot be created around someone else's habit")
    void creatingWithAnotherAccountsHabitIsRefused() {
        UUID victimHabit = seedHabit(victim, "Victim's morning run");

        assertThatThrownBy(() -> diaryRoutineService.createDiaryRoutine(
                routineWith("Stolen", victimHabit, null), attacker))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorKey())
                        .isEqualTo(ErrorKey.HABIT_NOT_OWNED));

        // And nothing was left half-built.
        assertThat(diaryRoutineService.getAllDiaryRoutines(attacker.getId())).isEmpty();
    }

    @Test
    @DisplayName("a routine cannot be created around someone else's task")
    void creatingWithAnotherAccountsTaskIsRefused() {
        UUID victimTask = seedTask(victim, "Victim's errand");

        assertThatThrownBy(() -> diaryRoutineService.createDiaryRoutine(
                routineWith("Stolen", null, victimTask), attacker))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorKey())
                        .isEqualTo(ErrorKey.TASK_NOT_OWNED));
    }

    /**
     * The hole the audit did not name: editing a routine to ADD a section goes through
     * the mapper, not the merge path, so it had the same gap as create.
     */
    @Test
    @DisplayName("a new section on edit cannot smuggle someone else's habit in either")
    void addingASectionWithAnotherAccountsHabitIsRefused() {
        UUID ownHabit = seedHabit(attacker, "My own habit");
        UUID victimHabit = seedHabit(victim, "Victim's habit");

        diaryRoutineService.createDiaryRoutine(routineWith("Mine", ownHabit, null), attacker);
        UUID routineId = diaryRoutineService.getAllDiaryRoutines(attacker.getId()).get(0).id();

        DiaryRoutineRequestDTO edited = new DiaryRoutineRequestDTO(
                "Mine", "lucide:sun", List.of(
                        section("Wake up", ownHabit, null),
                        section("Smuggled", victimHabit, null)));

        assertThatThrownBy(() -> diaryRoutineService.updateDiaryRoutine(routineId, edited, attacker.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorKey())
                        .isEqualTo(ErrorKey.HABIT_NOT_OWNED));
    }

    /** The check must refuse the other account, not every account. */
    @Test
    @DisplayName("your own habits and tasks still go in")
    void ownItemsAreStillAccepted() {
        UUID habit = seedHabit(attacker, "My habit");
        UUID task = seedTask(attacker, "My task");

        assertThatCode(() -> diaryRoutineService.createDiaryRoutine(
                routineWith("Mine", habit, task), attacker))
                .doesNotThrowAnyException();

        assertThat(diaryRoutineService.getAllDiaryRoutines(attacker.getId())).hasSize(1);
    }

    // -- helpers --

    private User freshUser(String email, String name) {
        userRepository.findByEmail(email).ifPresent(existing -> userService.deleteUser(existing));
        User fresh = new User();
        fresh.setName(name);
        fresh.setEmail(email);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        return userRepository.saveAndFlush(fresh);
    }

    private UUID seedCategory(User owner) {
        categoryService.createCategory(new CategoryRequestDTO(
                "Health", "lucide:heart", "seeded", ExperienceLevel.BEGINNER), owner.getId());
        return categoryService.getAllCategories(owner.getId()).get(0).id();
    }

    private UUID seedHabit(User owner, String name) {
        habitService.createHabit(new CreateHabitDTO(name, "seeded", "go",
                "lucide:droplet", 3, 2, List.of(seedCategory(owner)), ExperienceLevel.BEGINNER), owner.getId());
        return habitService.getHabits(owner.getId()).stream()
                .filter(h -> h.name().equals(name)).findFirst().orElseThrow().id();
    }

    private UUID seedTask(User owner, String name) {
        taskService.createTask(new CreateTaskRequestDTO(name, "seeded",
                "lucide:broom", 2, 2, List.of(seedCategory(owner)), false), owner.getId());
        return taskService.getAllTasks(owner.getId()).stream()
                .filter(t -> t.name().equals(name)).findFirst().orElseThrow().id();
    }

    private RoutineSectionRequestDTO section(String name, UUID habitId, UUID taskId) {
        return new RoutineSectionRequestDTO(
                null, name, "lucide:sunrise", LocalTime.of(7, 0), LocalTime.of(8, 0),
                taskId == null ? List.of()
                        : List.of(new TaskGroupDTO(null, taskId, LocalTime.of(7, 30), LocalTime.of(7, 40), null)),
                habitId == null ? List.of()
                        : List.of(new HabitGroupDTO(null, habitId, LocalTime.of(7, 0), LocalTime.of(7, 10), null)),
                false);
    }

    private DiaryRoutineRequestDTO routineWith(String name, UUID habitId, UUID taskId) {
        return new DiaryRoutineRequestDTO(name, "lucide:sun", List.of(section("Wake up", habitId, taskId)));
    }
}
