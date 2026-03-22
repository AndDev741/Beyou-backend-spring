package beyou.beyouapp.backend.domain.routine.snapshot;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class XpDecayCalculatorTest {
    private final XpDecayCalculator calculator = new XpDecayCalculator();

    @Test
    void gradual_oneDayLate_returns80Percent() {
        assertEquals(96.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.GRADUAL, LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21)), 0.01);
    }

    @Test
    void gradual_twoDaysLate_returns60Percent() {
        assertEquals(72.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.GRADUAL, LocalDate.of(2026, 3, 19), LocalDate.of(2026, 3, 21)), 0.01);
    }

    @Test
    void gradual_threeDaysLate_returns40Percent() {
        assertEquals(48.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.GRADUAL, LocalDate.of(2026, 3, 18), LocalDate.of(2026, 3, 21)), 0.01);
    }

    @Test
    void gradual_fourPlusDaysLate_returns20Percent() {
        assertEquals(24.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.GRADUAL, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 21)), 0.01);
    }

    @Test
    void flat_alwaysReturns50Percent() {
        assertEquals(60.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.FLAT, LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21)), 0.01);
        assertEquals(60.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.FLAT, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 21)), 0.01);
    }

    @Test
    void timeWindow_withinTwoDays_returnsFullXp() {
        assertEquals(120.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.TIME_WINDOW, LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21)), 0.01);
        assertEquals(120.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.TIME_WINDOW, LocalDate.of(2026, 3, 19), LocalDate.of(2026, 3, 21)), 0.01);
    }

    @Test
    void timeWindow_threePlusDays_returnsZero() {
        assertEquals(0.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.TIME_WINDOW, LocalDate.of(2026, 3, 18), LocalDate.of(2026, 3, 21)), 0.01);
    }

    @Test
    void sameDay_returnsFullXp_allStrategies() {
        LocalDate today = LocalDate.of(2026, 3, 21);
        assertEquals(120.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.GRADUAL, today, today), 0.01);
        assertEquals(120.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.FLAT, today, today), 0.01);
        assertEquals(120.0, calculator.calculateDecayedXp(120.0, XpDecayStrategy.TIME_WINDOW, today, today), 0.01);
    }
}
