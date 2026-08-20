package beyou.beyouapp.backend.unit.routine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.checkday.CheckDayRecorder;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;
import beyou.beyouapp.backend.domain.common.RefreshUiDtoBuilder;
import beyou.beyouapp.backend.domain.common.XpCalculatorService;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.routine.checks.CheckItemService;
import beyou.beyouapp.backend.domain.routine.checks.HabitGroupCheck;
import beyou.beyouapp.backend.domain.routine.itemGroup.HabitGroup;
import beyou.beyouapp.backend.domain.routine.itemGroup.ItemGroupService;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.DiaryRoutine;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.RoutineSection;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.HabitGroupRequestDTO;
import beyou.beyouapp.backend.domain.routine.specializedRoutines.dto.itemGroup.SkipGroupRequestDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.enums.ConstanceConfiguration;

/**
 * {@code checkDate} and {@code checkTime} have to describe the same moment.
 *
 * <p>They used not to. The date came from {@code UserDateResolver} in the owner's zone
 * while the time came from a bare {@code LocalTime.now()} in the server's, so the pair
 * disagreed for anyone not sitting in the server's zone: an hour apart for a Lisbon user in
 * summer, hours apart at a bigger offset. {@code SnapshotCheckMigrator} copies
 * {@code checkTime} into the snapshot history, so the contradiction outlived the live row.
 *
 * <p>Asserted as an offset rather than an exact clock reading, because the test cannot pin
 * the instant the service calls {@code now()}.
 */
@ExtendWith(MockitoExtension.class)
class CheckItemServiceClockZoneUnitTest {

    @Mock private ItemGroupService itemGroupService;
    @Mock private XpCalculatorService xpCalculatorService;
    @Mock private UserService userService;
    @Mock private RefreshUiDtoBuilder refreshUiDtoBuilder;
    @Mock private CheckDayRecorder checkDayRecorder;
    @Mock private EntityCheckDayRepository entityCheckDayRepository;

    private CheckItemService checkItemService;

    @BeforeEach
    void setUp() {
        checkItemService = new CheckItemService(itemGroupService, xpCalculatorService, userService,
                refreshUiDtoBuilder, checkDayRecorder, entityCheckDayRepository);
        lenient().when(refreshUiDtoBuilder.buildRefreshUiDto(any(), any(), any(), any(), any()))
                .thenReturn(new RefreshUiDTO(null, null, null, null));
    }

    @Test
    @DisplayName("Europe/Lisbon: the stamped time is the owner's clock, not the server's")
    void stampsLisbonClock() {
        assertStampedInZone("Europe/Lisbon");
    }

    @Test
    @DisplayName("a large offset makes the same defect unmissable")
    void stampsSaoPauloClock() {
        // One hour can pass by luck on a server that happens to sit an hour away. Three
        // cannot, which is why this zone is here alongside the one Beyou is deployed for.
        assertStampedInZone("America/Sao_Paulo");
    }

    @Test
    @DisplayName("an unparseable stored zone falls back instead of blocking the check")
    void unparseableZoneStillChecks() {
        // UserDateResolver.zoneOf promises this, and RoutineSnapshotSchedulerTest already
        // seeds an account with "INVALID/TIMEZONE". A check-in must never be the thing that
        // discovers a bad timezone string.
        HabitGroupCheck check = skipWithTimezone("INVALID/TIMEZONE");

        assertEquals(LocalDate.of(2024, 1, 1), check.getCheckDate());
        assertTrue(check.getCheckTime() != null, "the check still recorded a time");
    }

    private void assertStampedInZone(String zoneId) {
        LocalTime expected = LocalTime.now(ZoneId.of(zoneId));

        HabitGroupCheck check = skipWithTimezone(zoneId);

        LocalTime stamped = check.getCheckTime();
        long driftSeconds = Math.abs(Duration.between(
                LocalDateTime.of(LocalDate.EPOCH, expected),
                LocalDateTime.of(LocalDate.EPOCH, stamped)).getSeconds());

        // Generous, because the two readings are taken microseconds apart but could
        // straddle midnight in that zone; 60s is far below the smallest offset that
        // separates any two IANA zones.
        assertTrue(driftSeconds < 60 || driftSeconds > 86_340,
                "checkTime " + stamped + " is not the clock in " + zoneId + " (" + expected + ")");
    }

    private HabitGroupCheck skipWithTimezone(String zoneId) {
        UUID routineId = UUID.randomUUID();
        UUID habitGroupId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2024, 1, 1);

        HabitGroup habitGroup = buildHabitGroup(routineId, habitGroupId);
        when(itemGroupService.findHabitGroupByDTO(routineId, habitGroupId)).thenReturn(habitGroup);

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setConstanceConfiguration(ConstanceConfiguration.ANY);
        user.setCompletedDays(new HashSet<>());
        user.setTimezone(zoneId);
        habitGroup.getRoutineSection().getRoutine().setUser(user);

        checkItemService.skipOrUnskipItemGroup(new SkipGroupRequestDTO(
                routineId, null, new HabitGroupRequestDTO(habitGroupId, null), date, true));

        return habitGroup.getHabitGroupChecks().get(0);
    }

    private HabitGroup buildHabitGroup(UUID routineId, UUID habitGroupId) {
        Habit habit = new Habit();
        habit.setId(UUID.randomUUID());
        habit.setCategories(new ArrayList<>());

        HabitGroup habitGroup = new HabitGroup();
        habitGroup.setId(habitGroupId);
        habitGroup.setHabit(habit);
        habitGroup.setHabitGroupChecks(new ArrayList<>());

        RoutineSection section = new RoutineSection();
        section.setId(UUID.randomUUID());
        section.setHabitGroups(new ArrayList<>(List.of(habitGroup)));
        section.setTaskGroups(new ArrayList<>());

        DiaryRoutine routine = new DiaryRoutine();
        routine.setId(routineId);
        routine.setRoutineSections(new ArrayList<>(List.of(section)));

        section.setRoutine(routine);
        habitGroup.setRoutineSection(section);

        return habitGroup;
    }
}
