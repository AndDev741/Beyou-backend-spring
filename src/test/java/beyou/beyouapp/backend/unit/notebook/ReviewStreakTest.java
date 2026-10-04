package beyou.beyouapp.backend.unit.notebook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.notebook.card.ReviewStreak;

class ReviewStreakTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    @Test
    void aRunEndingTodayCounts() {
        assertThat(ReviewStreak.count(List.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)), TODAY)).isEqualTo(3);
    }

    /** Nobody has reviewed at nine in the morning yet; the streak must not read zero until they do. */
    @Test
    void aRunEndingYesterdayStillCounts() {
        assertThat(ReviewStreak.count(List.of(TODAY.minusDays(1), TODAY.minusDays(2)), TODAY)).isEqualTo(2);
    }

    @Test
    void aGapEndsTheRun() {
        assertThat(ReviewStreak.count(List.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(3)), TODAY)).isEqualTo(2);
        assertThat(ReviewStreak.count(List.of(TODAY.minusDays(2)), TODAY)).isZero();
        assertThat(ReviewStreak.count(List.of(), TODAY)).isZero();
    }
}
