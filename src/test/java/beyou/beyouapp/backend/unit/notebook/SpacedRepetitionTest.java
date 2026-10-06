package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.card.CardRating;
import beyou.beyouapp.backend.domain.notebook.card.SpacedRepetition;
import beyou.beyouapp.backend.domain.notebook.card.SpacedRepetition.State;

/** The scheduler's promises, one per test. Pure, so no Spring. */
class SpacedRepetitionTest {

    @Test
    void goodOnANewCardIsOneDayThenThreeThenIntervalTimesEase() {
        State first = SpacedRepetition.next(State.fresh(), CardRating.GOOD);
        State second = SpacedRepetition.next(first, CardRating.GOOD);
        State third = SpacedRepetition.next(second, CardRating.GOOD);

        assertThat(first.intervalDays()).isEqualTo(1);
        assertThat(second.intervalDays()).isEqualTo(3);
        assertThat(third.intervalDays()).isEqualTo(8); // round(3 * 2.5)
    }

    @Test
    void againForgetsTheCardAndLowersEaseButNotBelowTheFloor() {
        State learned = new State(20, 1.35, 5, 0);

        State forgot = SpacedRepetition.next(learned, CardRating.AGAIN);

        assertThat(forgot.intervalDays()).isZero();
        assertThat(forgot.reps()).isZero();
        assertThat(forgot.lapses()).isEqualTo(1);
        assertThat(forgot.ease()).isEqualTo(SpacedRepetition.MIN_EASE);
    }

    @Test
    void easyAlwaysWaitsLongerThanGood() {
        for (State s : new State[] {State.fresh(), new State(1, 2.5, 1, 0), new State(10, 1.3, 4, 2)}) {
            assertThat(SpacedRepetition.next(s, CardRating.EASY).intervalDays())
                    .isGreaterThan(SpacedRepetition.next(s, CardRating.GOOD).intervalDays());
        }
    }

    @Test
    void hardGrowsSlowlyAndCostsEase() {
        State s = new State(10, 2.5, 4, 0);

        State hard = SpacedRepetition.next(s, CardRating.HARD);

        assertThat(hard.intervalDays()).isEqualTo(12);
        assertThat(hard.ease()).isEqualTo(2.35);
    }

    @Test
    void noIntervalPassesAYear() {
        State s = new State(300, 3.0, 12, 0);

        assertThat(SpacedRepetition.next(s, CardRating.EASY).intervalDays()).isEqualTo(SpacedRepetition.MAX_INTERVAL_DAYS);
    }

    @Test
    void thePreviewIsWhatEachButtonWouldDo() {
        Map<CardRating, Integer> preview = SpacedRepetition.preview(new State(3, 2.5, 2, 0));

        assertThat(preview).containsEntry(CardRating.AGAIN, 0)
                .containsEntry(CardRating.GOOD, 8);
    }
}
