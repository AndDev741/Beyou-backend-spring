package beyou.beyouapp.backend.integration.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.calendar.CalendarService;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarDayDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarGoalDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarMoodDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarOverviewDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarRoutineSource;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskRequestDTO;
import beyou.beyouapp.backend.domain.calendar.dto.CalendarTaskResponseDTO;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.mood.MoodEntry;
import beyou.beyouapp.backend.domain.mood.MoodEntryRepository;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * What one day of the calendar shows, joined on the server.
 *
 * <p>"Now" is 2026-10-15T01:30Z, which is still Wednesday the 14th (22:30) in Sao Paulo, where the
 * owner lives, and already the 15th in UTC. Every "which day is today" decision below is therefore
 * wrong by a day if it is made in the server's zone instead of the owner's. The seeded routine runs
 * Monday to Wednesday. That makes the 12th (Monday) a past
 * scheduled day, the 13th (Tuesday) yesterday, the 14th today, the 15th (Thursday) a day off and
 * the 19th next Monday.
 */
@Transactional
class CalendarOverviewIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-15T01:30:00Z");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 13);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final LocalDate THURSDAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate NEXT_TUESDAY = LocalDate.of(2026, 10, 20);

    @Autowired private CalendarService calendarService;
    @Autowired private UserRepository userRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private RoutineSnapshotRepository snapshotRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private MoodEntryRepository moodEntryRepository;

    private User owner;
    private DiaryRoutine morning;

    @BeforeEach
    void seed() {
        owner = newUser("overview-owner");
        morning = newRoutine(owner, "Morning", Set.of(WeekDay.Monday, WeekDay.Tuesday, WeekDay.Wednesday));
        setClock(NOW);
    }

    @Test
    @DisplayName("one entry per day of the range, ascending, with the owner's today")
    void oneEntryPerDay() {
        CalendarOverviewDTO overview = calendarService.getOverview(owner, MONDAY, NEXT_TUESDAY);

        assertThat(overview.from()).isEqualTo(MONDAY);
        assertThat(overview.to()).isEqualTo(NEXT_TUESDAY);
        assertThat(overview.today())
                .as("22:30 on the 14th in Sao Paulo; UTC already says the 15th")
                .isEqualTo(TODAY);
        assertThat(overview.days()).extracting(CalendarDayDTO::date)
                .containsExactlyElementsOf(IntStream.rangeClosed(0, 8).mapToObj(MONDAY::plusDays).toList());
    }

    @Test
    @DisplayName("AE7: yesterday shows how the routine went; next Monday shows it from the schedule, no counts")
    void pastDayFromSnapshotFutureDayFromSchedule() {
        // The routine was renamed since; the past day keeps the name it had then.
        snapshot(YESTERDAY, "Morning (old name)", 9, 7, false);

        CalendarOverviewDTO overview = calendarService.getOverview(owner, MONDAY, NEXT_TUESDAY);

        assertThat(day(overview, YESTERDAY).routines()).containsExactly(new CalendarRoutineDTO(
                morning.getId(), "Morning (old name)", "icon", CalendarRoutineSource.SNAPSHOT, 9, 7, false));
        assertThat(day(overview, NEXT_MONDAY).routines()).containsExactly(new CalendarRoutineDTO(
                morning.getId(), "Morning", "icon", CalendarRoutineSource.SCHEDULE, null, null, false));
        assertThat(day(overview, THURSDAY).routines()).as("not a scheduled weekday").isEmpty();
    }

    @Test
    @DisplayName("today before its snapshot comes from the schedule")
    void todayBeforeItsRecordComesFromTheSchedule() {
        CalendarOverviewDTO overview = calendarService.getOverview(owner, TODAY, TODAY);

        assertThat(day(overview, TODAY).routines()).extracting(CalendarRoutineDTO::source)
                .containsExactly(CalendarRoutineSource.SCHEDULE);
    }

    @Test
    @DisplayName("today once its snapshot exists comes from the snapshot, counts and all")
    void todayOnceRecordedComesFromTheSnapshot() {
        snapshot(TODAY, "Morning", 4, 4, true);

        CalendarOverviewDTO overview = calendarService.getOverview(owner, TODAY, TODAY);

        assertThat(day(overview, TODAY).routines()).containsExactly(new CalendarRoutineDTO(
                morning.getId(), "Morning", "icon", CalendarRoutineSource.SNAPSHOT, 4, 4, true));
    }

    /**
     * The 12th was a scheduled Monday, but nothing was recorded for it. Drawing today's schedule
     * there would claim the routine ran on a day nobody knows about.
     *
     * <p>The range reaches today on purpose: a range wholly in the past never loads the schedule
     * at all, and would pass whatever the rule said.
     */
    @Test
    @DisplayName("a past scheduled day with no snapshot shows no routine")
    void pastDayWithoutSnapshotShowsNothing() {
        CalendarOverviewDTO overview = calendarService.getOverview(owner, MONDAY, TODAY);

        assertThat(day(overview, MONDAY).routines()).isEmpty();
        assertThat(day(overview, TODAY).routines()).as("the schedule was loaded").isNotEmpty();
    }

    @Test
    @DisplayName("goals show on their end date, archived ones never")
    void goalsOnTheirEndDate() {
        Goal due = newGoal("Read 12 books", FRIDAY, false, null);
        newGoal("Put away", FRIDAY, false, NOW);
        Goal done = newGoal("Run 5k", NEXT_TUESDAY, true, null);

        CalendarOverviewDTO overview = calendarService.getOverview(owner, MONDAY, NEXT_TUESDAY);

        assertThat(day(overview, FRIDAY).goals())
                .containsExactly(new CalendarGoalDTO(due.getId(), "Read 12 books", "icon", false));
        assertThat(day(overview, NEXT_TUESDAY).goals())
                .containsExactly(new CalendarGoalDTO(done.getId(), "Run 5k", "icon", true));
        assertThat(day(overview, TODAY).goals()).isEmpty();
    }

    @Test
    @DisplayName("mood carries the level and whether the day has a diary note, not the note itself")
    void moodLevelAndNoteFlag() {
        moodEntryRepository.saveAndFlush(new MoodEntry(owner, YESTERDAY, 4, "Slept badly, good talk", NOW));
        moodEntryRepository.saveAndFlush(new MoodEntry(owner, MONDAY, 2, null, NOW));

        CalendarOverviewDTO overview = calendarService.getOverview(owner, MONDAY, TODAY);

        assertThat(day(overview, YESTERDAY).mood()).isEqualTo(new CalendarMoodDTO(4, true));
        assertThat(day(overview, MONDAY).mood()).isEqualTo(new CalendarMoodDTO(2, false));
        assertThat(day(overview, TODAY).mood()).isNull();
    }

    @Test
    @DisplayName("AE6: a missed task stays on its own day, not done, and does not move to today")
    void missedTaskStaysOnItsDay() {
        UUID missed = calendarService.create(owner,
                new CalendarTaskRequestDTO("Call the bank", null, YESTERDAY, LocalTime.of(10, 0), 0)).id();

        CalendarOverviewDTO overview = calendarService.getOverview(owner, YESTERDAY, TODAY);

        assertThat(day(overview, YESTERDAY).tasks()).singleElement().satisfies(task -> {
            assertThat(task.id()).isEqualTo(missed);
            assertThat(task.completed()).isFalse();
        });
        assertThat(day(overview, TODAY).tasks()).isEmpty();
    }

    @Test
    @DisplayName("a day's tasks: timed first by time, then untimed in the order they were created")
    void taskOrder() {
        UUID untimedFirst = createAt(NOW, "untimed, first", null);
        UUID late = createAt(NOW.plusSeconds(1), "15:00", LocalTime.of(15, 0));
        UUID untimedSecond = createAt(NOW.plusSeconds(2), "untimed, second", null);
        UUID early = createAt(NOW.plusSeconds(3), "08:00", LocalTime.of(8, 0));

        CalendarOverviewDTO overview = calendarService.getOverview(owner, TODAY, TODAY);

        assertThat(day(overview, TODAY).tasks()).extracting(CalendarTaskResponseDTO::id)
                .containsExactly(early, late, untimedFirst, untimedSecond);
    }

    @Test
    @DisplayName("nothing of another account's shows up")
    void anotherAccountStaysOut() {
        User other = newUser("overview-other");
        DiaryRoutine theirs = newRoutine(other, "Theirs", Set.of(WeekDay.Wednesday));
        calendarService.create(other, new CalendarTaskRequestDTO("Their errand", null, TODAY, null, 0));
        moodEntryRepository.saveAndFlush(new MoodEntry(other, TODAY, 5, "theirs", NOW));
        newGoalFor(other, "Their goal", TODAY, false, null);

        CalendarDayDTO today = day(calendarService.getOverview(owner, TODAY, TODAY), TODAY);

        assertThat(today.tasks()).isEmpty();
        assertThat(today.mood()).isNull();
        assertThat(today.goals()).isEmpty();
        assertThat(today.routines()).extracting(CalendarRoutineDTO::routineId)
                .containsExactly(morning.getId())
                .doesNotContain(theirs.getId());
    }

    // ---- helpers ----

    private static CalendarDayDTO day(CalendarOverviewDTO overview, LocalDate date) {
        return overview.days().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
    }

    private UUID createAt(Instant at, String title, LocalTime time) {
        setClock(at);
        return calendarService.create(owner, new CalendarTaskRequestDTO(title, null, TODAY, time, 0)).id();
    }

    private void snapshot(LocalDate date, String name, int total, int checked, boolean completed) {
        RoutineSnapshot snapshot = new RoutineSnapshot();
        snapshot.setRoutine(morning);
        snapshot.setUser(owner);
        snapshot.setSnapshotDate(date);
        snapshot.setRoutineName(name);
        snapshot.setRoutineIconId("icon");
        snapshot.setStructureJson("{\"sections\":[]}");
        snapshot.setCompleted(completed);
        List<SnapshotCheck> checks = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            SnapshotCheck check = new SnapshotCheck();
            check.setSnapshot(snapshot);
            check.setItemType(SnapshotItemType.HABIT);
            check.setItemName("item " + i);
            check.setSectionName("Warm-up");
            check.setDifficulty(3);
            check.setImportance(3);
            check.setChecked(i < checked);
            // One unchecked item is a skip: a skip is an answer, but it is not a check.
            check.setSkipped(i == checked);
            checks.add(check);
        }
        snapshot.setChecks(checks);
        snapshotRepository.saveAndFlush(snapshot);
    }

    private Goal newGoal(String name, LocalDate endDate, boolean complete, Instant archivedAt) {
        return newGoalFor(owner, name, endDate, complete, archivedAt);
    }

    private Goal newGoalFor(User user, String name, LocalDate endDate, boolean complete, Instant archivedAt) {
        Goal goal = new Goal();
        goal.setName(name);
        goal.setIconId("icon");
        goal.setTargetValue(10.0);
        goal.setCurrentValue(complete ? 10.0 : 3.0);
        goal.setUnit("times");
        goal.setComplete(complete);
        goal.setStartDate(endDate.minusDays(30));
        goal.setEndDate(endDate);
        goal.setXpReward(0);
        goal.setUser(user);
        goal.setStatus(complete ? GoalStatus.COMPLETED : GoalStatus.IN_PROGRESS);
        goal.setTerm(GoalTerm.SHORT_TERM);
        goal.setCategories(new ArrayList<>());
        goal.setArchivedAt(archivedAt);
        return goalRepository.saveAndFlush(goal);
    }

    private DiaryRoutine newRoutine(User user, String name, Set<WeekDay> days) {
        Schedule schedule = new Schedule();
        schedule.setDays(new HashSet<>(days));
        schedule = scheduleRepository.saveAndFlush(schedule);

        DiaryRoutine routine = new DiaryRoutine();
        routine.setName(name);
        routine.setIconId("icon");
        routine.setUser(user);
        routine.setSchedule(schedule);
        routine.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        routine.setRoutineSections(new ArrayList<>());
        return diaryRoutineRepository.saveAndFlush(routine);
    }

    private void setClock(Instant instant) {
        Object target = AopTestUtils.getTargetObject(calendarService);
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
