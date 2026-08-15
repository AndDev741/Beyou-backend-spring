package beyou.beyouapp.backend.integration.routine;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleService;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.schedule.dto.CreateScheduleDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.DiaryRoutineRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.RoutineSectionRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where schedule rows go when nobody is looking.
 *
 * A schedule has exactly one way in: {@code routines.schedule_id}. The table itself is
 * an id, {@code schedule_days} hangs off it, and nothing in that chain names a user. So
 * a schedule with no routine pointing at it is not merely unused, it is unreachable —
 * no query by account can find it, and no query by account can prove it is gone. That
 * is what made this leak survive an entire code review: the tests that count rows for a
 * deleted user were all correct and all blind to it.
 *
 * It was found by hand, on a dev database, after a real account deletion. Two of the
 * three schedules in it belonged to nobody.
 *
 * Two ways to strand one, and they need different fixes. Deleting the routine is closed
 * by the cascade on {@code Routine.schedule}. REPLACING the routine's schedule is not:
 * nothing is removed there, so no cascade fires. Both are pinned here.
 *
 * Counts are taken as deltas rather than absolutes because the integration suite shares
 * one database, and an absolute zero would make this file fail for something another
 * test left behind.
 */
class ScheduleLifecycleIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "schedule-lifecycle@beyou.test";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired DiaryRoutineService diaryRoutineService;
    @Autowired ScheduleService scheduleService;
    @Autowired JdbcTemplate jdbc;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));

        User fresh = new User();
        fresh.setName("someone with a routine");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);
    }

    @Test
    @DisplayName("deleting a routine takes its schedule and days with it")
    void deletingARoutineTakesItsScheduleAlong() {
        long schedulesBefore = count("schedules");
        long daysBefore = count("schedule_days");

        UUID routineId = seedRoutine("Morning");
        scheduleService.create(new CreateScheduleDTO(
                Set.of(WeekDay.Monday, WeekDay.Wednesday), routineId), user.getId());

        assertThat(count("schedules")).isEqualTo(schedulesBefore + 1);
        assertThat(count("schedule_days")).isEqualTo(daysBefore + 2);

        // The path a normal user takes, and the one the cascade covers.
        diaryRoutineService.deleteDiaryRoutine(routineId, user.getId());

        assertThat(count("schedules"))
                .as("a deleted routine must not leave a schedule nobody can reach")
                .isEqualTo(schedulesBefore);
        assertThat(count("schedule_days")).isEqualTo(daysBefore);
    }

    @Test
    @DisplayName("rescheduling replaces the schedule instead of abandoning it")
    void reschedulingDoesNotStrandTheOldRow() {
        long schedulesBefore = count("schedules");
        long daysBefore = count("schedule_days");

        UUID routineId = seedRoutine("Evening");
        scheduleService.create(new CreateScheduleDTO(
                Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday), routineId), user.getId());

        // Changing the days goes through create again — the route is POST and the entity
        // is new every time, so this is the operation, not a misuse of it.
        scheduleService.create(new CreateScheduleDTO(
                Set.of(WeekDay.Friday), routineId), user.getId());

        assertThat(count("schedules"))
                .as("one routine, one schedule, however many times it is rescheduled")
                .isEqualTo(schedulesBefore + 1);
        assertThat(count("schedule_days"))
                .as("and the three days of the schedule it replaced are gone with it")
                .isEqualTo(daysBefore + 1);

        userService.deleteUser(user);
        assertThat(count("schedules")).isEqualTo(schedulesBefore);
        assertThat(count("schedule_days")).isEqualTo(daysBefore);
    }

    private UUID seedRoutine(String name) {
        diaryRoutineService.createDiaryRoutine(new DiaryRoutineRequestDTO(
                name, "lucide:sun", List.of(new RoutineSectionRequestDTO(
                        null, "A section", "lucide:sunrise",
                        LocalTime.of(7, 0), LocalTime.of(8, 0),
                        List.of(), List.of(), false))), user.getId());
        return diaryRoutineService.getAllDiaryRoutines(user.getId()).stream()
                .filter(routine -> routine.name().equals(name))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private long count(String table) {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return rows == null ? 0 : rows;
    }
}
