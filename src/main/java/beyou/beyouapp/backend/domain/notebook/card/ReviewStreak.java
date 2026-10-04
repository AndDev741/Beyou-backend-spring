package beyou.beyouapp.backend.domain.notebook.card;

import java.time.LocalDate;
import java.util.List;

/**
 * Consecutive days with at least one review.
 *
 * <p>The run may end yesterday as well as today. At 9 in the morning nobody has reviewed yet,
 * and a streak that reads 0 until the first card of the day is a streak that punishes people
 * for being awake. Two days without a review end it.
 */
public final class ReviewStreak {

    /** How far back the query looks. A longer streak shows as this many days. */
    public static final int LOOKBACK_DAYS = 400;

    private ReviewStreak() {}

    /** {@code days} is distinct and newest first, as the repository returns it. */
    public static int count(List<LocalDate> days, LocalDate today) {
        if (days.isEmpty()) return 0;
        LocalDate expected = days.get(0).equals(today) ? today : today.minusDays(1);
        int streak = 0;
        for (LocalDate day : days) {
            if (day.isAfter(today)) continue;
            if (!day.equals(expected)) break;
            streak++;
            expected = expected.minusDays(1);
        }
        return streak;
    }
}
