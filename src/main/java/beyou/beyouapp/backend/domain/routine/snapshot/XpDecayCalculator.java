package beyou.beyouapp.backend.domain.routine.snapshot;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Component
public class XpDecayCalculator {

    public double calculateDecayedXp(double baseXp, XpDecayStrategy strategy, LocalDate snapshotDate, LocalDate userLocalDate) {
        long daysLate = Math.abs(ChronoUnit.DAYS.between(snapshotDate, userLocalDate));
        if (daysLate == 0) return baseXp;

        double multiplier = switch (strategy) {
            case GRADUAL -> gradualMultiplier(daysLate);
            case FLAT -> 0.5;
            case TIME_WINDOW -> daysLate <= 2 ? 1.0 : 0.0;
        };

        return baseXp * multiplier;
    }

    private double gradualMultiplier(long daysLate) {
        return switch ((int) Math.min(daysLate, 4)) {
            case 1 -> 0.8;
            case 2 -> 0.6;
            case 3 -> 0.4;
            default -> 0.2;
        };
    }
}
