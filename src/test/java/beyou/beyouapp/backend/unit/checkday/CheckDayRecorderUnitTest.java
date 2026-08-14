package beyou.beyouapp.backend.unit.checkday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckDayRecorder;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver.Standing;
import beyou.beyouapp.backend.user.User;

@ExtendWith(MockitoExtension.class)
class CheckDayRecorderUnitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);

    @Mock
    private EntityCheckDayRepository entityCheckDayRepository;

    @InjectMocks
    private CheckDayRecorder checkDayRecorder;

    private User user;
    private UUID habitId;
    private CheckProgress progress;
    private List<EntityCheckDay> stored;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        habitId = UUID.randomUUID();
        progress = new CheckProgress();
        stored = new ArrayList<>();
    }

    @Test
    void checkingWritesOneDoneRowAndTheTotalReadsOne() {
        givenHistory();

        CheckProgress result = record(CheckDayOutcome.DONE, TODAY);

        EntityCheckDay saved = captureSaved();
        assertThat(saved.getOwnerType()).isEqualTo(CheckDayOwnerType.HABIT);
        assertThat(saved.getOwnerId()).isEqualTo(habitId);
        assertThat(saved.getDay()).isEqualTo(TODAY);
        assertThat(saved.getOutcome()).isEqualTo(CheckDayOutcome.DONE);
        assertThat(saved.getUser()).isSameAs(user);

        assertThat(result.getTotalCheckIns()).isEqualTo(1);
        assertThat(result.getCurrentStreak()).isEqualTo(1);
        assertThat(result.getFirstCheckInDate()).isEqualTo(TODAY);
        assertThat(result.getLastCheckInDate()).isEqualTo(TODAY);
    }

    @Test
    void theOwnersScalarsAreUpdatedInPlaceSoHibernateSeesTheChange() {
        // The embeddable handed in is the one hanging off the habit. Mutating it is what
        // makes the update happen; returning a fresh object and forgetting to copy it back
        // would leave the habit's columns untouched.
        givenHistory(row(TODAY.minusDays(1), CheckDayOutcome.DONE));

        CheckProgress returned = record(CheckDayOutcome.DONE, TODAY);

        assertThat(progress.getCurrentStreak()).isEqualTo(2);
        assertThat(progress.getTotalCheckIns()).isEqualTo(2);
        assertThat(progress.getBestStreak()).isEqualTo(2);
        assertThat(progress.getLastCheckInDate()).isEqualTo(TODAY);
        assertThat(returned.getCurrentStreak()).isEqualTo(progress.getCurrentStreak());
    }

    @Test
    void checkingTwiceOnTheSameDayLeavesOneRowAndOneIncrement() {
        EntityCheckDay existing = row(TODAY, CheckDayOutcome.DONE);
        givenHistory(existing);

        CheckProgress result = record(CheckDayOutcome.DONE, TODAY);

        EntityCheckDay saved = captureSaved();
        assertThat(saved)
                .as("the row for the day is overwritten, not duplicated")
                .isSameAs(existing);
        assertThat(result.getTotalCheckIns()).isEqualTo(1);
    }

    @Test
    void rewritingADayToAnAbsenceOutcomeTakesTheTotalBackToZeroAndNeverBelow() {
        givenHistory(row(TODAY, CheckDayOutcome.DONE));

        CheckProgress result = record(CheckDayOutcome.NOT_SCHEDULED, TODAY);

        assertThat(captureSaved().getOutcome()).isEqualTo(CheckDayOutcome.NOT_SCHEDULED);
        assertThat(result.getTotalCheckIns()).isZero();
        assertThat(result.getFirstCheckInDate()).isNull();
        assertThat(result.getLastCheckInDate()).isNull();
    }

    @Test
    void skippingLeavesTheTotalAndTheStreakUntouched() {
        givenHistory(
                row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                row(TODAY.minusDays(1), CheckDayOutcome.DONE));

        CheckProgress result = record(CheckDayOutcome.SKIPPED, TODAY);

        assertThat(captureSaved().getOutcome()).isEqualTo(CheckDayOutcome.SKIPPED);
        assertThat(result.getTotalCheckIns()).isEqualTo(2);
        assertThat(result.getCurrentStreak()).isEqualTo(2);
    }

    @Test
    void theRecordSurvivesAStreakThatBreaks() {
        progress.setBestStreak(9);
        givenHistory(row(TODAY.minusDays(1), CheckDayOutcome.MISSED));

        CheckProgress result = record(CheckDayOutcome.DONE, TODAY);

        assertThat(result.getCurrentStreak()).isEqualTo(1);
        assertThat(result.getBestStreak()).isEqualTo(9);
    }

    @Test
    void theLockIsTakenOnTheUserBeforeTheEntityAndBeforeAnythingIsRead() {
        givenHistory();

        record(CheckDayOutcome.DONE, TODAY);

        ArgumentCaptor<Integer> classIds = ArgumentCaptor.forClass(Integer.class);
        InOrder order = inOrder(entityCheckDayRepository);
        order.verify(entityCheckDayRepository, org.mockito.Mockito.times(2))
                .lockCheckOwner(classIds.capture(), anyInt());
        order.verify(entityCheckDayRepository)
                .findByOwnerTypeAndOwnerIdOrderByDayAsc(CheckDayOwnerType.HABIT, habitId);
        order.verify(entityCheckDayRepository).save(any(EntityCheckDay.class));

        assertThat(classIds.getAllValues())
                .as("user first, entity second — the order XpCalculatorService writes those rows in")
                .containsExactly(
                        CheckDayOwnerType.USER.name().hashCode(),
                        CheckDayOwnerType.HABIT.name().hashCode());
    }

    @Test
    void aUserOwnedRowLocksItsOwnKeyOnlyOnce() {
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.USER, user.getId())).thenReturn(List.of());
        when(entityCheckDayRepository.save(any(EntityCheckDay.class))).thenAnswer(i -> i.getArgument(0));

        checkDayRecorder.record(user, CheckDayOwnerType.USER, user.getId(), progress, TODAY,
                CheckDayOutcome.DONE);

        verify(entityCheckDayRepository)
                .lockCheckOwner(eq(CheckDayOwnerType.USER.name().hashCode()), anyInt());
    }

    @Test
    void aNullProgressStillWritesTheRow() {
        givenHistory();

        CheckProgress result = checkDayRecorder.record(
                user, CheckDayOwnerType.HABIT, habitId, null, TODAY, CheckDayOutcome.DONE);

        assertThat(captureSaved().getOutcome()).isEqualTo(CheckDayOutcome.DONE);
        assertThat(result.getCurrentStreak()).isEqualTo(1);
    }

    @Test
    void aMissingOwnerOrDayIsRejectedBeforeAnyLockIsTaken() {
        assertThatThrownBy(() -> checkDayRecorder.record(
                null, CheckDayOwnerType.HABIT, habitId, progress, TODAY, CheckDayOutcome.DONE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> checkDayRecorder.record(
                user, CheckDayOwnerType.HABIT, habitId, progress, null, CheckDayOutcome.DONE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> checkDayRecorder.record(
                user, CheckDayOwnerType.HABIT, habitId, progress, TODAY, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> checkDayRecorder.record(
                user, null, habitId, progress, TODAY, CheckDayOutcome.DONE))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(entityCheckDayRepository);
    }

    @Test
    void aUserWithNoIdIsRejectedRatherThanWritingAnUnattributableRow() {
        User idless = new User();

        assertThatThrownBy(() -> checkDayRecorder.record(
                idless, CheckDayOwnerType.HABIT, habitId, progress, TODAY, CheckDayOutcome.DONE))
                .isInstanceOf(IllegalArgumentException.class);

        verify(entityCheckDayRepository, never()).save(any());
    }

    @Test
    void theAbsenceOutcomeFollowsWhereTheOwnerStandsAgainstTheSchedule() {
        boolean dayClosed = true;

        assertThat(CheckDayRecorder.absenceOutcome(Standing.ORPHANED, dayClosed))
                .isEqualTo(CheckDayOutcome.NOT_IN_ROUTINE);
        assertThat(CheckDayRecorder.absenceOutcome(new Standing(true, false), dayClosed))
                .isEqualTo(CheckDayOutcome.NOT_SCHEDULED);
        assertThat(CheckDayRecorder.absenceOutcome(new Standing(true, true), dayClosed))
                .isEqualTo(CheckDayOutcome.MISSED);
        assertThat(CheckDayRecorder.absenceOutcome(null, dayClosed))
                .isEqualTo(CheckDayOutcome.NOT_IN_ROUTINE);
    }

    @Test
    void anOpenDayCarriesNoRowInsteadOfAPrematureMissed() {
        assertThat(CheckDayRecorder.absenceOutcome(new Standing(true, true), false))
                .as("Scheduled but the day is still running — nothing true to say yet, so no row")
                .isNull();
    }

    @Test
    void anOpenDayStillStampsTheAbsencesThatAreFactsAboutTheSchedule() {
        assertThat(CheckDayRecorder.absenceOutcome(Standing.ORPHANED, false))
                .as("Belonging to no routine is true at any hour")
                .isEqualTo(CheckDayOutcome.NOT_IN_ROUTINE);
        assertThat(CheckDayRecorder.absenceOutcome(new Standing(true, false), false))
                .as("Not covering this weekday is true at any hour")
                .isEqualTo(CheckDayOutcome.NOT_SCHEDULED);
    }

    @Test
    void clearingADayDropsItsRowAndRecomputesFromWhatRemains() {
        givenStoredHistoryOnly(
                row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                row(TODAY.minusDays(1), CheckDayOutcome.DONE),
                row(TODAY, CheckDayOutcome.DONE));

        CheckProgress after = checkDayRecorder.clearDay(
                user, CheckDayOwnerType.HABIT, habitId, progress, TODAY);

        verify(entityCheckDayRepository).deleteOwnerDay(CheckDayOwnerType.HABIT, habitId, TODAY);
        assertThat(after.getTotalCheckIns())
                .as("The cleared day no longer counts as a check-in")
                .isEqualTo(2);
        assertThat(after.getCurrentStreak())
                .as("The two earlier days still run unbroken up to the cleared one")
                .isEqualTo(2);
    }

    @Test
    void clearingADayNeverLowersTheRecord() {
        progress.setBestStreak(9);
        givenStoredHistoryOnly(row(TODAY, CheckDayOutcome.DONE));

        CheckProgress after = checkDayRecorder.clearDay(
                user, CheckDayOwnerType.HABIT, habitId, progress, TODAY);

        assertThat(after.getBestStreak()).isEqualTo(9);
    }

    private CheckProgress record(CheckDayOutcome outcome, LocalDate day) {
        return checkDayRecorder.record(user, CheckDayOwnerType.HABIT, habitId, progress, day, outcome);
    }

    /** The read alone. clearDay deletes rather than saving, so stubbing save would go unused. */
    private void givenStoredHistoryOnly(EntityCheckDay... rows) {
        stored = new ArrayList<>(List.of(rows));
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.HABIT, habitId)).thenReturn(stored);
    }

    private void givenHistory(EntityCheckDay... rows) {
        stored = new ArrayList<>(List.of(rows));
        when(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(
                CheckDayOwnerType.HABIT, habitId)).thenReturn(stored);
        when(entityCheckDayRepository.save(any(EntityCheckDay.class))).thenAnswer(i -> i.getArgument(0));
    }

    private EntityCheckDay captureSaved() {
        ArgumentCaptor<EntityCheckDay> captor = ArgumentCaptor.forClass(EntityCheckDay.class);
        verify(entityCheckDayRepository).save(captor.capture());
        return captor.getValue();
    }

    private EntityCheckDay row(LocalDate day, CheckDayOutcome outcome) {
        return new EntityCheckDay(user, CheckDayOwnerType.HABIT, habitId, day, outcome);
    }
}
