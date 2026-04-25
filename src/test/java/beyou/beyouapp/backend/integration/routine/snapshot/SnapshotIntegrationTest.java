package beyou.beyouapp.backend.integration.routine.snapshot;

import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.schedule.Schedule;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduleRepository;
import beyou.beyouapp.backend.domain.routine.schedule.WeekDay;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshot;
import beyou.beyouapp.backend.domain.routine.snapshot.RoutineSnapshotRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheck;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotCheckRepository;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;
import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotService;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotMonthResponseDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.dto.SnapshotResponseDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutineRepository;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class SnapshotIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SnapshotService snapshotService;
    @Autowired private RoutineSnapshotRepository snapshotRepository;
    @Autowired private SnapshotCheckRepository snapshotCheckRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private DiaryRoutineRepository diaryRoutineRepository;
    @Autowired private EntityManager entityManager;

    private User user;
    private Habit habit;
    private DiaryRoutine routine;
    private Schedule schedule;

    @BeforeEach
    void setUp() {
        // XpByLevel is seeded automatically by SeedOrchestrator (CommandLineRunner).

        // -- User --
        user = new User();
        user.setName("Integration Test User");
        user.setEmail("snapshot-test-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user.setGoogleAccount(false);
        user.setTimezone("UTC");
        user.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        user = userRepository.saveAndFlush(user);

        // -- Habit --
        habit = new Habit();
        habit.setName("Morning Exercise");
        habit.setIconId("icon-exercise");
        habit.setImportance(3);
        habit.setDificulty(2);
        habit.setDescription("Daily workout");
        habit.setMotivationalPhrase("Stay strong");
        habit.setCategories(new ArrayList<>());
        habit.setConstance(0);
        habit.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        habit.setUser(user);
        habit = habitRepository.saveAndFlush(habit);

        // -- Schedule --
        schedule = new Schedule();
        schedule.setDays(Set.of(WeekDay.Monday, WeekDay.Wednesday, WeekDay.Friday));
        schedule = scheduleRepository.saveAndFlush(schedule);

        // -- DiaryRoutine with RoutineSection containing a HabitGroup --
        routine = new DiaryRoutine();
        routine.setName("Morning Routine");
        routine.setIconId("icon-morning");
        routine.setUser(user);
        routine.setSchedule(schedule);
        routine.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

        RoutineSection section = new RoutineSection();
        section.setName("Warm-up Section");
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

        // Re-fetch to ensure all IDs are populated and relationships are navigable
        routine = diaryRoutineRepository.findById(routine.getId()).orElseThrow();
        user = userRepository.findById(user.getId()).orElseThrow();
    }

    // -----------------------------------------------------------------------
    // createSnapshot
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("createSnapshot")
    class CreateSnapshotTests {

        @Test
        @DisplayName("should persist snapshot with correct fields and checks")
        void createSnapshot_persistsSnapshotAndChecks() {
            LocalDate today = LocalDate.of(2026, 3, 21);

            RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, user, today);

            // Snapshot persisted
            assertThat(snapshot.getId()).isNotNull();
            assertThat(snapshot.getSnapshotDate()).isEqualTo(today);
            assertThat(snapshot.getRoutineName()).isEqualTo("Morning Routine");
            assertThat(snapshot.getRoutineIconId()).isEqualTo("icon-morning");
            assertThat(snapshot.isCompleted()).isFalse();
            assertThat(snapshot.getStructureJson()).isNotBlank();
            assertThat(snapshot.getUser().getId()).isEqualTo(user.getId());
            assertThat(snapshot.getRoutine().getId()).isEqualTo(routine.getId());

            // Checks
            assertThat(snapshot.getChecks()).hasSize(1);
            SnapshotCheck check = snapshot.getChecks().get(0);
            assertThat(check.getId()).isNotNull();
            assertThat(check.getItemType()).isEqualTo(SnapshotItemType.HABIT);
            assertThat(check.getItemName()).isEqualTo("Morning Exercise");
            assertThat(check.getItemIconId()).isEqualTo("icon-exercise");
            assertThat(check.getSectionName()).isEqualTo("Warm-up Section");
            assertThat(check.getDifficulty()).isEqualTo(2);
            assertThat(check.getImportance()).isEqualTo(3);
            assertThat(check.isChecked()).isFalse();
            assertThat(check.isSkipped()).isFalse();
            assertThat(check.getXpGenerated()).isEqualTo(0.0);

            // Verify persistence via repository
            assertThat(snapshotRepository.findById(snapshot.getId())).isPresent();
            assertThat(snapshotCheckRepository.findAllBySnapshotId(snapshot.getId())).hasSize(1);
        }

        @Test
        @DisplayName("should contain valid JSON structure with section and habit info")
        void createSnapshot_structureJsonContainsExpectedData() {
            RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, user, LocalDate.of(2026, 3, 21));

            String json = snapshot.getStructureJson();
            assertThat(json).contains("\"sections\"");
            assertThat(json).contains("Warm-up Section");
            assertThat(json).contains("Morning Exercise");
            assertThat(json).contains("HABIT");
            assertThat(json).contains("icon-exercise");
        }

        @Test
        @DisplayName("should link originalGroupId and originalItemId to the HabitGroup and Habit")
        void createSnapshot_checksReferenceOriginalEntities() {
            RoutineSnapshot snapshot = snapshotService.createSnapshot(routine, user, LocalDate.of(2026, 3, 21));

            SnapshotCheck check = snapshot.getChecks().get(0);
            // originalItemId should match the habit's id
            assertThat(check.getOriginalItemId()).isEqualTo(habit.getId());
            // originalGroupId should match the habitGroup's id
            HabitGroup hg = routine.getRoutineSections().get(0).getHabitGroups().get(0);
            assertThat(check.getOriginalGroupId()).isEqualTo(hg.getId());
        }
    }

    // -----------------------------------------------------------------------
    // getSnapshot
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("getSnapshot")
    class GetSnapshotTests {

        @Test
        @DisplayName("should return correct DTO for existing snapshot")
        void getSnapshot_returnsCorrectDTO() {
            LocalDate date = LocalDate.of(2026, 3, 21);
            snapshotService.createSnapshot(routine, user, date);

            SnapshotResponseDTO dto = snapshotService.getSnapshot(routine.getId(), date, user.getId());

            assertThat(dto.id()).isNotNull();
            assertThat(dto.snapshotDate()).isEqualTo(date);
            assertThat(dto.routineName()).isEqualTo("Morning Routine");
            assertThat(dto.routineIconId()).isEqualTo("icon-morning");
            assertThat(dto.completed()).isFalse();
            assertThat(dto.structure()).isNotBlank();
            assertThat(dto.checks()).hasSize(1);
            assertThat(dto.checks().get(0).itemType()).isEqualTo(SnapshotItemType.HABIT);
            assertThat(dto.checks().get(0).itemName()).isEqualTo("Morning Exercise");
            assertThat(dto.checks().get(0).difficulty()).isEqualTo(2);
            assertThat(dto.checks().get(0).importance()).isEqualTo(3);
        }

        @Test
        @DisplayName("should throw SNAPSHOT_NOT_FOUND when no snapshot exists for the date")
        void getSnapshot_throwsWhenNotFound() {
            LocalDate missingDate = LocalDate.of(2026, 1, 1);

            assertThatThrownBy(() ->
                snapshotService.getSnapshot(routine.getId(), missingDate, user.getId())
            )
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorKey())
                    .isEqualTo(ErrorKey.SNAPSHOT_NOT_FOUND));
        }

        @Test
        @DisplayName("should throw SNAPSHOT_NOT_OWNED when userId does not match snapshot owner")
        void getSnapshot_throwsWhenNotOwned() {
            LocalDate date = LocalDate.of(2026, 3, 21);
            snapshotService.createSnapshot(routine, user, date);

            UUID otherUserId = UUID.randomUUID();
            assertThatThrownBy(() ->
                snapshotService.getSnapshot(routine.getId(), date, otherUserId)
            )
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorKey())
                    .isEqualTo(ErrorKey.SNAPSHOT_NOT_OWNED));
        }
    }

    // -----------------------------------------------------------------------
    // getSnapshotDatesForMonth
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("getSnapshotDatesForMonth")
    class GetSnapshotDatesForMonthTests {

        @Test
        @DisplayName("should return all snapshot dates within a given month")
        void getSnapshotDatesForMonth_returnsCorrectDates() {
            // Create snapshots on three dates within March 2026
            LocalDate march5 = LocalDate.of(2026, 3, 5);
            LocalDate march15 = LocalDate.of(2026, 3, 15);
            LocalDate march28 = LocalDate.of(2026, 3, 28);

            snapshotService.createSnapshot(routine, user, march5);
            snapshotService.createSnapshot(routine, user, march15);
            snapshotService.createSnapshot(routine, user, march28);

            // Also create one in February — should NOT appear
            snapshotService.createSnapshot(routine, user, LocalDate.of(2026, 2, 15));

            SnapshotMonthResponseDTO result = snapshotService.getSnapshotDatesForMonth(
                    routine.getId(), "2026-03", user.getId());

            assertThat(result.dates()).hasSize(3);
            assertThat(result.dates()).containsExactly(march5, march15, march28);
        }

        @Test
        @DisplayName("should return empty list when no snapshots exist for the month")
        void getSnapshotDatesForMonth_returnsEmptyWhenNone() {
            SnapshotMonthResponseDTO result = snapshotService.getSnapshotDatesForMonth(
                    routine.getId(), "2026-06", user.getId());

            assertThat(result.dates()).isEmpty();
        }

        @Test
        @DisplayName("should throw ROUTINE_NOT_FOUND when routine does not exist")
        void getSnapshotDatesForMonth_throwsWhenRoutineNotFound() {
            UUID fakeRoutineId = UUID.randomUUID();
            assertThatThrownBy(() ->
                snapshotService.getSnapshotDatesForMonth(fakeRoutineId, "2026-03", user.getId())
            )
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorKey())
                    .isEqualTo(ErrorKey.ROUTINE_NOT_FOUND));
        }

        @Test
        @DisplayName("should throw ROUTINE_NOT_OWNED when userId does not match routine owner")
        void getSnapshotDatesForMonth_throwsWhenNotOwned() {
            UUID otherUserId = UUID.randomUUID();
            assertThatThrownBy(() ->
                snapshotService.getSnapshotDatesForMonth(routine.getId(), "2026-03", otherUserId)
            )
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorKey())
                    .isEqualTo(ErrorKey.ROUTINE_NOT_OWNED));
        }
    }

    // -----------------------------------------------------------------------
    // Unique constraint: routine + date
    // -----------------------------------------------------------------------
    @Nested
    @DisplayName("unique constraint on routine + date")
    class UniqueConstraintTests {

        @Test
        @DisplayName("should reject duplicate snapshot for the same routine and date")
        void duplicateSnapshot_shouldFail() {
            LocalDate date = LocalDate.of(2026, 3, 21);
            snapshotService.createSnapshot(routine, user, date);

            // Force the first save to be written to the DB so the unique constraint fires
            entityManager.flush();

            assertThatThrownBy(() -> {
                snapshotService.createSnapshot(routine, user, date);
                entityManager.flush();
            }).isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("should allow snapshots on different dates for the same routine")
        void differentDates_shouldSucceed() {
            RoutineSnapshot snap1 = snapshotService.createSnapshot(routine, user, LocalDate.of(2026, 3, 21));
            RoutineSnapshot snap2 = snapshotService.createSnapshot(routine, user, LocalDate.of(2026, 3, 22));

            assertThat(snap1.getId()).isNotEqualTo(snap2.getId());
            assertThat(snapshotRepository.findAllByRoutineId(routine.getId())).hasSize(2);
        }

        @Test
        @DisplayName("should allow snapshots on the same date for different routines")
        void differentRoutines_sameDateShouldSucceed() {
            LocalDate date = LocalDate.of(2026, 3, 21);

            // Create a second routine
            Schedule schedule2 = new Schedule();
            schedule2.setDays(Set.of(WeekDay.Tuesday));
            schedule2 = scheduleRepository.saveAndFlush(schedule2);

            DiaryRoutine routine2 = new DiaryRoutine();
            routine2.setName("Evening Routine");
            routine2.setIconId("icon-evening");
            routine2.setUser(user);
            routine2.setSchedule(schedule2);
            routine2.setXpProgress(new XpProgress(0D, 0, 0D, 50D));

            RoutineSection section2 = new RoutineSection();
            section2.setName("Cool-down Section");
            section2.setIconId("icon-cooldown");
            section2.setStartTime(LocalTime.of(20, 0));
            section2.setEndTime(LocalTime.of(21, 0));
            section2.setOrderIndex(0);
            section2.setFavorite(false);
            section2.setRoutine(routine2);

            HabitGroup hg2 = new HabitGroup();
            hg2.setHabit(habit);
            hg2.setRoutineSection(section2);
            hg2.setStartTime(LocalTime.of(20, 0));
            hg2.setEndTime(LocalTime.of(20, 30));
            hg2.setHabitGroupChecks(new ArrayList<>());

            section2.setHabitGroups(List.of(hg2));
            section2.setTaskGroups(new ArrayList<>());
            routine2.setRoutineSections(List.of(section2));
            routine2 = diaryRoutineRepository.saveAndFlush(routine2);
            entityManager.flush();
            entityManager.clear();

            // Re-fetch all entities since entityManager.clear() detaches everything
            routine2 = diaryRoutineRepository.findById(routine2.getId()).orElseThrow();
            DiaryRoutine refetchedRoutine = diaryRoutineRepository.findById(routine.getId()).orElseThrow();
            User refetchedUser = userRepository.findById(user.getId()).orElseThrow();

            RoutineSnapshot snap1 = snapshotService.createSnapshot(refetchedRoutine, refetchedUser, date);
            RoutineSnapshot snap2 = snapshotService.createSnapshot(routine2, refetchedUser, date);

            assertThat(snap1.getId()).isNotEqualTo(snap2.getId());
            assertThat(snap1.getRoutineName()).isEqualTo("Morning Routine");
            assertThat(snap2.getRoutineName()).isEqualTo("Evening Routine");
        }
    }
}
