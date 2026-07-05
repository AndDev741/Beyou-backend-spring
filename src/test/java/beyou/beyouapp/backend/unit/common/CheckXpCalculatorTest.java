package beyou.beyouapp.backend.unit.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.common.CheckXpCalculator;

class CheckXpCalculatorTest {

    @Test
    @DisplayName("additive base: BASE_XP * (difficulty + importance), no streak")
    void baseFormulaNoStreak() {
        assertEquals(10.0, CheckXpCalculator.calculate(1, 1, 0)); // 5 * 2
        assertEquals(25.0, CheckXpCalculator.calculate(2, 3, 0)); // 5 * 5
        assertEquals(50.0, CheckXpCalculator.calculate(5, 5, 0)); // 5 * 10 (was 250 under old 10*d*i)
    }

    @Test
    @DisplayName("streak bonus adds 1% per day, capped at +50%")
    void streakBonus() {
        assertEquals(50.0, CheckXpCalculator.calculate(5, 5, 0));   // no streak
        assertEquals(63.0, CheckXpCalculator.calculate(5, 5, 25));  // 50 * 1.25 = 62.5 -> 63 (round half-up)
        assertEquals(75.0, CheckXpCalculator.calculate(5, 5, 50));  // 50 * 1.5 = cap
        assertEquals(75.0, CheckXpCalculator.calculate(5, 5, 100)); // still capped
        assertEquals(75.0, CheckXpCalculator.calculate(5, 5, 999)); // still capped
    }

    @Test
    @DisplayName("difficulty/importance are clamped to 1..5")
    void clampsInputs() {
        assertEquals(CheckXpCalculator.calculate(1, 1, 0), CheckXpCalculator.calculate(0, 0, 0));
        assertEquals(CheckXpCalculator.calculate(5, 5, 0), CheckXpCalculator.calculate(9, 9, 0));
    }

    @Test
    @DisplayName("negative streak is treated as no streak (no negative bonus)")
    void negativeStreakSafe() {
        assertEquals(CheckXpCalculator.calculate(3, 3, 0), CheckXpCalculator.calculate(3, 3, -5));
    }

    @Test
    @DisplayName("level curve 50*L^2 has strictly increasing per-level deltas (monotonic)")
    void curveIsMonotonic() {
        long prevDelta = -1;
        long prevThreshold = 0;
        for (int level = 1; level <= 100; level++) {
            long threshold = Math.round(50.0 * level * level);
            long delta = threshold - prevThreshold;
            assertTrue(delta > prevDelta,
                    "delta must strictly increase; broke at level " + level);
            prevDelta = delta;
            prevThreshold = threshold;
        }
    }
}
