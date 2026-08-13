package beyou.beyouapp.backend.unit.checkday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.CheckHistoryService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.dto.CheckDayResponseDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;

/**
 * The range arithmetic and the gap filling, with the repository mocked — everything below
 * is a decision the service makes before or after the one query it runs, so none of it
 * needs a database to pin down.
 */
@ExtendWith(MockitoExtension.class)
class CheckHistoryServiceUnitTest {

    private static final UUID OWNER = UUID.randomUUID();

    @Mock
    private EntityCheckDayRepository entityCheckDayRepository;

    @InjectMocks
    private CheckHistoryService checkHistoryService;

    private User user;

    /**
     * The user's own today, not a literal. The service resolves the default range against
     * {@code UserDateResolver}, so a hardcoded date here would pass on the day it was
     * written and fail every day after.
     */
    private LocalDate TODAY;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        // Fixed zone so "today" never depends on the machine the suite runs on.
        user.setTimezone("UTC");
        TODAY = LocalDate.now(ZoneOffset.UTC);
    }

    @Nested
    class TheRange {

        @Test
        void returnsOneEntryPerDayOrderedOldestFirst() {
            stubRows();

            CheckDayResponseDTO response = history(TODAY.minusDays(4), TODAY);

            assertThat(response.days()).hasSize(5);
            assertThat(response.days()).extracting(CheckDayResponseDTO.Day::day)
                    .containsExactly(TODAY.minusDays(4), TODAY.minusDays(3), TODAY.minusDays(2),
                            TODAY.minusDays(1), TODAY);
        }

        @Test
        void omittingBothEndsReturnsTheLastTwentyEightDaysEndingToday() {
            stubRows();

            CheckDayResponseDTO response = history(null, null);

            assertThat(CheckHistoryService.DEFAULT_RANGE_DAYS).isEqualTo(28);
            assertThat(response.to()).as("ends on the owner's today").isEqualTo(TODAY);
            assertThat(response.from()).isEqualTo(TODAY.minusDays(27));
            assertThat(response.days()).hasSize(28);
        }

        @Test
        void omittingOnlyFromBacksUpFromTheGivenEnd() {
            stubRows();
            LocalDate end = TODAY.minusDays(100);

            CheckDayResponseDTO response = history(null, end);

            assertThat(response.to()).isEqualTo(end);
            assertThat(response.from()).isEqualTo(end.minusDays(27));
        }

        @Test
        void aRangeWiderThanTheCapIsClampedAndTheResponseReportsTheEffectiveRange() {
            stubRows();
            LocalDate wayBack = TODAY.minusYears(5);

            CheckDayResponseDTO response = history(wayBack, TODAY);

            // Clamped, not refused — the caller asked for real data, just too much of it.
            assertThat(response.from())
                    .as("the recent end is kept and the older end moves up")
                    .isEqualTo(TODAY.minusDays(CheckHistoryService.MAX_RANGE_DAYS - 1L));
            assertThat(response.to()).isEqualTo(TODAY);
            assertThat(response.days()).hasSize(CheckHistoryService.MAX_RANGE_DAYS);

            // And the query is issued for the clamped range, not the requested one —
            // otherwise the cap would bound the payload while the database still scanned
            // five years.
            ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(entityCheckDayRepository)
                    .findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                            any(), any(), any(), fromCaptor.capture(), any());
            assertThat(fromCaptor.getValue()).isEqualTo(response.from());
        }

        @Test
        void aRangeExactlyAtTheCapIsLeftAlone() {
            stubRows();
            LocalDate from = TODAY.minusDays(CheckHistoryService.MAX_RANGE_DAYS - 1L);

            CheckDayResponseDTO response = history(from, TODAY);

            assertThat(response.from()).isEqualTo(from);
            assertThat(response.days()).hasSize(CheckHistoryService.MAX_RANGE_DAYS);
        }

        @Test
        void aSingleDayRangeIsOneEntry() {
            stubRows();

            CheckDayResponseDTO response = history(TODAY, TODAY);

            assertThat(response.days()).hasSize(1);
            assertThat(response.days().get(0).day()).isEqualTo(TODAY);
        }

        @Test
        void anExtremeToIsRefusedBeforeTheDefaultBackstepCanUnderflow() {
            // GET /check-history?ownerType=USER&to=-999999999-01-01. With `from` omitted the
            // service derives it as `to` minus 27 days, which walks off the bottom of the
            // LocalDate range and throws DateTimeException — not an IllegalArgumentException,
            // so GlobalExceptionHandler has no mapping for it and the caller gets a 500 while
            // both AOP aspects write a full stack trace at ERROR. Two stack traces per
            // request, on an endpoint anyone can call sixty times a minute.
            assertThatThrownBy(() -> history(null, LocalDate.MIN))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorKey())
                    .isEqualTo(ErrorKey.INVALID_REQUEST);

            verify(entityCheckDayRepository, never())
                    .findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                            any(), any(), any(), any(), any());
        }

        @Test
        void anExtremeToInTheOtherDirectionIsRefusedToo() {
            assertThatThrownBy(() -> history(null, LocalDate.MAX))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorKey())
                    .isEqualTo(ErrorKey.INVALID_REQUEST);
        }

        @Test
        void tomorrowIsStillAnAcceptableEndForAUserAheadOfTheServer() {
            // The upper bound is today plus one day, not today. A client resolving its own
            // date a few hours ahead of the zone the service reads must not be refused.
            stubRows();

            CheckDayResponseDTO response = history(TODAY, TODAY.plusDays(1));

            assertThat(response.to()).isEqualTo(TODAY.plusDays(1));
            assertThat(response.days()).hasSize(2);
        }

        @Test
        void anExtremeFromIsFlooredRatherThanRefused() {
            // The older end is the lenient one: a caller asking for more than there is gets
            // what there is. Only `to` drives the arithmetic that can underflow.
            stubRows();

            CheckDayResponseDTO response = history(LocalDate.MIN, TODAY);

            assertThat(response.from())
                    .isEqualTo(TODAY.minusDays(CheckHistoryService.MAX_RANGE_DAYS - 1L));
            assertThat(response.days()).hasSize(CheckHistoryService.MAX_RANGE_DAYS);
        }

        @Test
        void anInvertedRangeIsRefusedRatherThanAnsweredEmpty() {
            // Reachable only from a hand-edited query string. An empty answer would read as
            // "this owner has no history", which is a wrong answer rather than a refused one.
            assertThatThrownBy(() -> history(TODAY, TODAY.minusDays(1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must not be after");

            verify(entityCheckDayRepository, never())
                    .findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                            any(), any(), any(), any(), any());
        }
    }

    @Nested
    class Outcomes {

        @Test
        void everyStoredOutcomeCrossesTheWireUnderItsOwnName() {
            when(rowsFor(TODAY.minusDays(4), TODAY)).thenReturn(List.of(
                    row(TODAY.minusDays(4), CheckDayOutcome.DONE),
                    row(TODAY.minusDays(3), CheckDayOutcome.SKIPPED),
                    row(TODAY.minusDays(2), CheckDayOutcome.MISSED),
                    row(TODAY.minusDays(1), CheckDayOutcome.NOT_SCHEDULED),
                    row(TODAY, CheckDayOutcome.NOT_IN_ROUTINE)));

            CheckDayResponseDTO response = history(TODAY.minusDays(4), TODAY);

            assertThat(response.days()).extracting(CheckDayResponseDTO.Day::outcome)
                    .containsExactly(
                            CheckDayResponseDTO.Outcome.DONE,
                            CheckDayResponseDTO.Outcome.SKIPPED,
                            CheckDayResponseDTO.Outcome.MISSED,
                            CheckDayResponseDTO.Outcome.NOT_SCHEDULED,
                            CheckDayResponseDTO.Outcome.NOT_IN_ROUTINE);
        }

        @Test
        void aDayWithNoStoredRowReadsAsUnknownRatherThanBeingOmitted() {
            // R18 — the client renders a gap instead of guessing. A response that dropped
            // the day would leave "nobody closed this day" and "nothing was expected" as
            // the same shape on the wire.
            when(rowsFor(TODAY.minusDays(2), TODAY)).thenReturn(List.of(
                    row(TODAY.minusDays(2), CheckDayOutcome.DONE),
                    row(TODAY, CheckDayOutcome.DONE)));

            CheckDayResponseDTO response = history(TODAY.minusDays(2), TODAY);

            assertThat(response.days()).extracting(CheckDayResponseDTO.Day::outcome)
                    .containsExactly(
                            CheckDayResponseDTO.Outcome.DONE,
                            CheckDayResponseDTO.Outcome.UNKNOWN,
                            CheckDayResponseDTO.Outcome.DONE);
        }

        @Test
        void anOwnerWithNoRowsAtAllReadsAsAnAllUnknownRange() {
            stubRows();

            CheckDayResponseDTO response = history(TODAY.minusDays(6), TODAY);

            assertThat(response.days()).hasSize(7);
            assertThat(response.days()).extracting(CheckDayResponseDTO.Day::outcome)
                    .containsOnly(CheckDayResponseDTO.Outcome.UNKNOWN);
        }
    }

    @Nested
    class OwnerResolution {

        @Test
        void theUserOwnerTypeWithNoOwnerIdResolvesToTheAuthenticatedUser() {
            stubRows();

            CheckDayResponseDTO response = checkHistoryService.history(
                    user, CheckDayOwnerType.USER, null, TODAY, TODAY);

            assertThat(response.ownerId()).isEqualTo(user.getId());
            verify(entityCheckDayRepository)
                    .findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                            eq(user.getId()), eq(CheckDayOwnerType.USER), eq(user.getId()),
                            any(), any());
        }

        @Test
        void aNonUserOwnerTypeWithNoOwnerIdIsRefused() {
            assertThatThrownBy(() -> checkHistoryService.history(
                    user, CheckDayOwnerType.HABIT, null, TODAY, TODAY))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("ownerId is required");
        }

        @Test
        void aMissingOwnerTypeIsRefused() {
            assertThatThrownBy(() -> checkHistoryService.history(user, null, OWNER, TODAY, TODAY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorKey())
                    .isEqualTo(ErrorKey.INVALID_REQUEST);
        }

        @Test
        void anotherUsersOwnerIdReadsAsAllUnknownAndNeverAsANotOwnedError() {
            // The account is part of the predicate, so somebody else's habit id simply
            // matches nothing. Refusing instead would confirm the id exists, and would also
            // be wrong for a habit that has been deleted while its history survives (R8).
            UUID someoneElsesHabit = UUID.randomUUID();
            when(entityCheckDayRepository.findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                    eq(user.getId()), eq(CheckDayOwnerType.HABIT), eq(someoneElsesHabit), any(), any()))
                    .thenReturn(List.of());

            CheckDayResponseDTO response = checkHistoryService.history(
                    user, CheckDayOwnerType.HABIT, someoneElsesHabit, TODAY.minusDays(2), TODAY);

            assertThat(response.days()).hasSize(3);
            assertThat(response.days()).extracting(CheckDayResponseDTO.Day::outcome)
                    .containsOnly(CheckDayResponseDTO.Outcome.UNKNOWN);
        }

        @Test
        void theAccountIsAlwaysPartOfThePredicate() {
            stubRows();

            history(TODAY, TODAY);

            verify(entityCheckDayRepository)
                    .findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                            eq(user.getId()), eq(CheckDayOwnerType.HABIT), eq(OWNER),
                            eq(TODAY), eq(TODAY));
        }
    }

    // --- helpers ------------------------------------------------------------

    private CheckDayResponseDTO history(LocalDate from, LocalDate to) {
        return checkHistoryService.history(user, CheckDayOwnerType.HABIT, OWNER, from, to);
    }

    /** No rows anywhere in the requested window. */
    private void stubRows() {
        when(entityCheckDayRepository.findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                any(), any(), any(), any(), any())).thenReturn(new ArrayList<>());
    }

    private List<EntityCheckDay> rowsFor(LocalDate from, LocalDate to) {
        return entityCheckDayRepository.findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                user.getId(), CheckDayOwnerType.HABIT, OWNER, from, to);
    }

    private EntityCheckDay row(LocalDate day, CheckDayOutcome outcome) {
        return new EntityCheckDay(user, CheckDayOwnerType.HABIT, OWNER, day, outcome);
    }
}
