package beyou.beyouapp.backend.domain.goal.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import beyou.beyouapp.backend.domain.goal.Goal;

public class GoalXpCalculator {

    public static Double calculateXp(Goal goal) {
        int baseXp = getBaseXp(goal.getTargetValue());

        double difficulty = getDifficultyMultiplier(goal.getTargetValue());
        double urgency = getUrgencyMultiplier(goal.getStartDate(), goal.getEndDate());
        double consistency = getConsistencyMultiplier(goal);

        return (double) Math.round(baseXp * difficulty * urgency * consistency);
    }

    private static int getBaseXp(double targetValue) {
        if (targetValue < 10) return 50;
        if (targetValue < 50) return 100;
        if (targetValue < 200) return 200;
        return 300;
    }

    private static double getDifficultyMultiplier(double targetValue) {
        if (targetValue >= 200) return 2.0;
        if (targetValue >= 50) return 1.5;
        if (targetValue >= 10) return 1.2;
        return 1.0;
    }

    private static double getUrgencyMultiplier(LocalDate start, LocalDate end) {
        long days = ChronoUnit.DAYS.between(start, end);
        if (days <= 7) return 1.5;
        if (days <= 30) return 1.2;
        return 1.0;
    }

    private static double getConsistencyMultiplier(Goal goal) {
        if (goal.getEndDate().isAfter(LocalDate.now())) {
            return 1.3;
        }
        return 1.0;
    }
}
