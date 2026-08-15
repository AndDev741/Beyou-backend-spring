package beyou.beyouapp.backend.user;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.CreateHabitDTO;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.HabitGroupDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.TaskGroupDTO;
import beyou.beyouapp.backend.domain.task.TaskService;
import beyou.beyouapp.backend.domain.task.dto.CreateTaskRequestDTO;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the download actually contains, for an account that has been used.
 *
 * The export sits directly beside the delete button and is offered as the thing you
 * take before leaving, so the standard it has to meet is not "the endpoint returns
 * 200" — {@code UserExportControllerTest} covers that against a mocked service, which
 * means it would go on passing if this class returned an empty map. The standard is
 * that a person who takes the file and then deletes the account has not silently lost
 * anything they were told they were keeping.
 *
 * It failed that once already: routines, the days they run on and every XP and streak
 * counter in the account were absent, while the copy beside the button named routines
 * and XP by name as things deletion would destroy. So the assertions here walk into
 * the structure rather than checking a key exists — a routine reduced to its name is
 * the shape the bug had.
 */
class UserExportCompletenessIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "export-completeness@beyou.test";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired UserExportService exportService;
    @Autowired CategoryService categoryService;
    @Autowired HabitService habitService;
    @Autowired TaskService taskService;
    @Autowired DiaryRoutineService diaryRoutineService;
    @Autowired ScheduleService scheduleService;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));

        User fresh = new User();
        fresh.setName("someone packing up");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);

        // The export reads whoever is authenticated, so the test has to be them.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the export carries the routine, the days it runs on and every progress counter")
    @SuppressWarnings("unchecked")
    void exportsTheStructureAndTheProgress() {
        UUID userId = user.getId();

        categoryService.createCategory(new CategoryRequestDTO(
                "Health", "lucide:heart", "seeded", ExperienceLevel.BEGINNER), userId);
        UUID categoryId = categoryService.getAllCategories(userId).get(0).id();

        habitService.createHabit(new CreateHabitDTO("Drink water", "seeded", "stay hydrated",
                "lucide:droplet", 3, 2, List.of(categoryId), ExperienceLevel.BEGINNER), userId);
        UUID habitId = habitService.getHabits(userId).get(0).id();

        taskService.createTask(new CreateTaskRequestDTO("Tidy the desk", "seeded",
                "lucide:broom", 2, 2, List.of(categoryId), false), userId);
        UUID taskId = taskService.getAllTasks(userId).get(0).id();

        diaryRoutineService.createDiaryRoutine(new DiaryRoutineRequestDTO(
                "Morning", "lucide:sun", List.of(new RoutineSectionRequestDTO(
                        null, "Wake up", "lucide:sunrise", LocalTime.of(7, 0), LocalTime.of(8, 0),
                        List.of(new TaskGroupDTO(null, taskId, LocalTime.of(7, 30), LocalTime.of(7, 40), null)),
                        List.of(new HabitGroupDTO(null, habitId, LocalTime.of(7, 0), LocalTime.of(7, 10), null)),
                        false))), userId);
        UUID routineId = diaryRoutineService.getAllDiaryRoutines(userId).get(0).id();

        scheduleService.create(new CreateScheduleDTO(
                Set.of(WeekDay.Monday, WeekDay.Wednesday), routineId), userId);

        Map<String, Object> export = exportService.exportUserData();

        List<Map<String, Object>> routines = (List<Map<String, Object>>) export.get("routines");
        assertThat(routines).as("a routine the account owns has to appear at all").hasSize(1);

        Map<String, Object> routine = routines.get(0);
        assertThat(routine.get("name")).isEqualTo("Morning");
        assertThat(routine.get("progress")).isNotNull();
        assertThat(routine.get("streak")).isNotNull();

        // The days, which are what turn a list of habits into a routine that happens.
        Map<String, Object> schedule = (Map<String, Object>) routine.get("schedule");
        assertThat(schedule).as("a scheduled routine must carry its days").isNotNull();
        assertThat((Set<WeekDay>) schedule.get("days"))
                .containsExactlyInAnyOrder(WeekDay.Monday, WeekDay.Wednesday);

        // The arrangement: habits and tasks survive in their own sections of the file,
        // but which one sits in which section at what time lives only here.
        List<Map<String, Object>> sections = (List<Map<String, Object>>) routine.get("sections");
        assertThat(sections).hasSize(1);
        Map<String, Object> section = sections.get(0);
        assertThat(section.get("name")).isEqualTo("Wake up");
        assertThat(section.get("startTime")).isEqualTo(LocalTime.of(7, 0));

        List<Map<String, Object>> habitGroups = (List<Map<String, Object>>) section.get("habits");
        assertThat(habitGroups).hasSize(1);
        assertThat(habitGroups.get(0).get("habitId"))
                .as("groups reference the habits section rather than copying it")
                .isEqualTo(habitId);

        List<Map<String, Object>> taskGroups = (List<Map<String, Object>>) section.get("tasks");
        assertThat(taskGroups).hasSize(1);
        assertThat(taskGroups.get(0).get("taskId")).isEqualTo(taskId);

        // Levels and streaks: the numbers a person is likeliest to want a record of.
        Map<String, Object> profile = (Map<String, Object>) export.get("profile");
        assertThat((Map<String, Object>) profile.get("progress")).containsKeys("xp", "level");
        assertThat((Map<String, Object>) profile.get("streak")).containsKeys("currentStreak", "bestStreak");
        assertThat(profile.get("timezone")).isEqualTo(user.getTimezone());

        List<Map<String, Object>> habits = (List<Map<String, Object>>) export.get("habits");
        assertThat((Map<String, Object>) habits.get(0).get("progress")).containsKeys("xp", "level");
        assertThat((Map<String, Object>) habits.get(0).get("streak")).containsKey("bestStreak");

        List<Map<String, Object>> categories = (List<Map<String, Object>>) export.get("categories");
        assertThat((Map<String, Object>) categories.get(0).get("progress")).containsKeys("xp", "level");

        // Whatever stays out stays out on the record, so the file can be read as a
        // whole instead of spot-checked against the app.
        assertThat((Map<String, Object>) export.get("notIncluded"))
                .containsKeys("routineSnapshots", "agentChat", "credentials");

        userService.deleteUser(user);
    }
}
