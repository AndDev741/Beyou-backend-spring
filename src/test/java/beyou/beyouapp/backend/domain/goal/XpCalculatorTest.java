package beyou.beyouapp.backend.domain.goal;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.goal.util.GoalXpCalculator;

import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

public class XpCalculatorTest {

    private Goal buildGoal(double targetValue, LocalDate start, LocalDate end, boolean completeBefore) {
        Goal goal = new Goal();
        goal.setTargetValue(targetValue);
        goal.setStartDate(start);
        goal.setEndDate(end);
        goal.setComplete(true);
        if (completeBefore) {
            goal.setEndDate(LocalDate.now().plusDays(5));
        }
        return goal;
    }

    @Test
    void testSmallGoalDaily() {
        Goal goal = buildGoal(2, LocalDate.now(), LocalDate.now().plusDays(1), false);
        double xp = GoalXpCalculator.calculateXp(goal);

        // Base = 50, dificulty = 1.0, urgency = 1.5, constance = 1.0
        assertEquals(98.0, xp);
    }

    @Test
    void testMediumGoalMonthly() {
        Goal goal = buildGoal(20, LocalDate.now(), LocalDate.now().plusDays(30), false);
        double xp = GoalXpCalculator.calculateXp(goal);

        // Base = 100, dificulty = 1.2, urgency = 1.2, constance = 1.0
        assertEquals(187.0, xp);
    }

    @Test
    void testBigGoalMonthlyWithBonus() {
        Goal goal = buildGoal(100, LocalDate.now(), LocalDate.now().plusDays(30), true);
        double xp = GoalXpCalculator.calculateXp(goal);

        // Base = 200, dificulty = 1.5, urgency = 1.2, constance = 1.3
        // 200 * 1.5 * 1.2 * 1.3 = 468
        assertEquals(585.0, xp);
    }

    @Test
    void testHugeGoalLongTerm() {
        Goal goal = buildGoal(500, LocalDate.now(), LocalDate.now().plusDays(120), false);
        double xp = GoalXpCalculator.calculateXp(goal);

        // Base = 300, dificulty = 2.0, urgency = 1.0, constance = 1.0
        assertEquals(780.0, xp);
    }
}

