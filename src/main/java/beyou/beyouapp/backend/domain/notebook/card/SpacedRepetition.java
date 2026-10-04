package beyou.beyouapp.backend.domain.notebook.card;

import java.util.EnumMap;
import java.util.Map;

/**
 * The scheduler: given a card's state and an answer, when does the card come back.
 *
 * <p>SM-2 as Anki adapted it, in whole days, because that is the shape people already know
 * from Anki and the one the four buttons promise:
 * <ul>
 *   <li>AGAIN: the card is forgotten. Ease drops 0.2, repetitions reset, it is due again today
 *       (the client puts it back at the end of the session).</li>
 *   <li>HARD: remembered with effort. Ease drops 0.15, the interval grows by 1.2.</li>
 *   <li>GOOD: the ordinary answer. One day, then three, then interval times ease.</li>
 *   <li>EASY: ease rises 0.15, and the interval jumps further than GOOD would.</li>
 * </ul>
 *
 * <p>Ease never goes below 1.3, the SM-2 floor that stops a card from collapsing into a daily
 * chore. The interval never exceeds a year: a card that is a year away is a card the person no
 * longer studies, and "due in 2031" is not information anyone can use.
 *
 * <p>Pure on purpose. No clock and no repository, so every rule here is a unit test.
 */
public final class SpacedRepetition {

    public static final double START_EASE = 2.5;
    public static final double MIN_EASE = 1.3;
    public static final int MAX_INTERVAL_DAYS = 365;

    private SpacedRepetition() {}

    public record State(int intervalDays, double ease, int reps, int lapses) {
        public static State fresh() {
            return new State(0, START_EASE, 0, 0);
        }
    }

    public static State next(State s, CardRating rating) {
        return switch (rating) {
            case AGAIN -> new State(0, Math.max(MIN_EASE, s.ease() - 0.2), 0, s.lapses() + 1);
            case HARD -> new State(
                    cap(Math.max(1, (int) Math.round(Math.max(s.intervalDays(), 1) * 1.2))),
                    Math.max(MIN_EASE, s.ease() - 0.15), s.reps() + 1, s.lapses());
            case GOOD -> new State(cap(goodInterval(s)), s.ease(), s.reps() + 1, s.lapses());
            case EASY -> {
                int good = goodInterval(s);
                int easy = s.reps() == 0
                        ? 4
                        : (int) Math.round(Math.max(s.intervalDays(), 1) * s.ease() * 1.3);
                yield new State(cap(Math.max(easy, good + 1)), s.ease() + 0.15, s.reps() + 1, s.lapses());
            }
        };
    }

    /** What each button would do to this card, in days, for the labels under them. */
    public static Map<CardRating, Integer> preview(State s) {
        Map<CardRating, Integer> intervals = new EnumMap<>(CardRating.class);
        for (CardRating rating : CardRating.values()) {
            intervals.put(rating, next(s, rating).intervalDays());
        }
        return intervals;
    }

    private static int goodInterval(State s) {
        if (s.reps() == 0) return 1;
        if (s.reps() == 1) return 3;
        return Math.max(s.intervalDays() + 1, (int) Math.round(s.intervalDays() * s.ease()));
    }

    private static int cap(int days) {
        return Math.min(days, MAX_INTERVAL_DAYS);
    }
}
