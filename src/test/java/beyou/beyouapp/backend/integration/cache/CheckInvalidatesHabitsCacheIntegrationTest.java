package beyou.beyouapp.backend.integration.cache;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.domain.habit.dto.HabitResponseDTO;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.CheckGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

/**
 * R16 — checking a habit off in a routine has to reach the habits list immediately.
 *
 * <p>{@code HabitService.getHabits} is {@code @Cacheable("habits")} with a thirty-minute
 * TTL, and the check scalars now ride {@code HabitResponseDTO} (R2/R3). Without an
 * eviction on the check path, tapping a habit raises its streak and total in the database
 * while the list endpoint keeps serving the old numbers for up to half an hour — the card
 * the user just tapped repaints from stale data, and nothing anywhere fails.
 *
 * <p>The eviction lives one layer above {@code CheckItemService}, in
 * {@code DiaryRoutineService.checkAndUncheckGroup} and {@code skipOrUnskipGroup}, which
 * every caller goes through: {@code RoutineController} and the agent's
 * {@code Tools.checkRoutineItem}/{@code skipRoutineItem} all call the outer methods.
 * That placement is what this test pins. It exercises the service seam rather than
 * verifying a mock call, so moving the eviction (or removing it from either method) fails
 * here regardless of which class ends up owning it.
 */
@Transactional
class CheckInvalidatesHabitsCacheIntegrationTest extends AbstractIntegrationTest {

    @Autowired private HabitService habitService;
    @Autowired private DiaryRoutineService diaryRoutineService;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private EntityManager entityManager;

    private User user;
    private Habit habit;
    private DiaryRoutine routine;
    private UUID habitGroupId;

    @BeforeEach
    void setUp() {
        Cache habits = cacheManager.getCache("habits");
        assertThat(habits).isNotNull();
        habits.clear();

        user = new User();
        user.setName("Cache Test User");
        user.setEmail("habits-cache-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user.setGoogleAccount(false);
        user.setTimezone("UTC");
        user.setCompletedDays(new HashSet<>());
        user.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        user.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        user = userRepository.saveAndFlush(user);

        habit = new Habit();
        habit.setName("Morning Exercise");
        habit.setIconId("icon-exercise");
        habit.setImportance(3);
        habit.setDificulty(2);
        habit.setDescription("Daily workout");
        habit.setMotivationalPhrase("Stay strong");
        habit.setCategories(new ArrayList<>());
        habit.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        habit.setUser(user);
        habit = habitRepository.saveAndFlush(habit);

        // Every weekday, so the check lands on a scheduled day whichever day the suite runs.
        Schedule schedule = new Schedule();
        schedule.setDays(new HashSet<>(Arrays.asList(WeekDay.values())));
        schedule = scheduleRepository.saveAndFlush(schedule);

        routine = new DiaryRoutine();
        routine.setName("Morning Routine");
        routine.setIconId("icon-morning");
        routine.setUser(user);
        routine.setSchedule(schedule);
        routine.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

        RoutineSection section = new RoutineSection();
        section.setName("Warm-up");
        section.setIconId("icon-warmup");
        section.setStartTime(LocalTime.of(6, 0));
        section.setEndTime(LocalTime.of(7, 0));
        section.setOrderIndex(0);
        section.setFavorite(false);
        section.setRoutine(routine);

        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setHabit(habit);
        habitGroup.setRoutineSection(section);
        habitGroup.setStartTime(LocalTime.of(6, 0));
        habitGroup.setEndTime(LocalTime.of(6, 30));
        habitGroup.setHabitGroupChecks(new ArrayList<>());

        section.setHabitGroups(List.of(habitGroup));
        section.setTaskGroups(new ArrayList<>());
        routine.setRoutineSections(List.of(section));

        routine = diaryRoutineRepository.saveAndFlush(routine);
        entityManager.flush();
        entityManager.clear();

        routine = diaryRoutineRepository.findById(routine.getId()).orElseThrow();
        user = userRepository.findById(user.getId()).orElseThrow();
        habitGroupId = routine.getRoutineSections().get(0).getHabitGroups().get(0).getId();
    }

    @Test
    void checkingAHabitOffMakesTheHabitsListShowTheNewScalars() {
        List<HabitResponseDTO> before = habitService.getHabits(user.getId());
        assertThat(before).singleElement()
                .satisfies(dto -> {
                    assertThat(dto.totalCheckIns()).isZero();
                    assertThat(dto.currentStreak()).isZero();
                });
        // The list really is cached now — otherwise a stale read could never happen and
        // the assertion below would pass for the wrong reason.
        assertThat(cachedHabits()).isNotNull();

        diaryRoutineService.checkAndUncheckGroup(
                new CheckGroupRequestDTO(routine.getId(), null,
                        new HabitGroupRequestDTO(habitGroupId, null), LocalDate.now()),
                user.getId());

        List<HabitResponseDTO> after = habitService.getHabits(user.getId());
        assertThat(after).singleElement()
                .satisfies(dto -> {
                    assertThat(dto.totalCheckIns())
                            .as("the check-in the user just made, not the cached zero")
                            .isEqualTo(1);
                    assertThat(dto.currentStreak()).isEqualTo(1);
                });
    }

    @Test
    void skippingAHabitAlsoInvalidatesTheHabitsList() {
        // The skip path writes a SKIPPED row and re-derives the same scalars, so it needs
        // the same eviction. It sits in a different method, so one can be fixed and the
        // other left behind.
        habitService.getHabits(user.getId());
        assertThat(cachedHabits()).isNotNull();

        diaryRoutineService.skipOrUnskipGroup(
                new SkipGroupRequestDTO(routine.getId(), null,
                        new HabitGroupRequestDTO(habitGroupId, null), LocalDate.now(), true),
                user.getId());

        assertThat(cachedHabits())
                .as("a skip leaves the habits list to be rebuilt, not served from before it")
                .isNull();
    }

    @SuppressWarnings("unchecked")
    private List<HabitResponseDTO> cachedHabits() {
        Cache.ValueWrapper wrapper = cacheManager.getCache("habits").get(user.getId());
        return wrapper == null ? null : (List<HabitResponseDTO>) wrapper.get();
    }
}
