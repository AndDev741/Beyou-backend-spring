package beyou.beyouapp.backend.domain.common;

/**
 * Central earn formula for habit/task check-ins. All balance knobs live here so
 * tuning game feel is a one-file change, not a hunt across services.
 *
 * <p>Replaces the old inline {@code 10 * dificulty * importance} (range 10..250,
 * a 25x spread). The additive base gives a gentler 5x spread and a streak
 * multiplier finally rewards the {@code constance} already tracked per habit.
 *
 * <pre>
 *   xp = BASE_XP * (difficulty + importance) * (1 + min(streak * STREAK_STEP, STREAK_CAP))
 * </pre>
 *
 * Decay for late check-ins is applied by the caller (see XpDecayCalculator), not here.
 */
public final class CheckXpCalculator {

    private CheckXpCalculator() {}

    static final int BASE_XP = 5;            // XP per (difficulty + importance) point
    static final double STREAK_STEP = 0.01;  // +1% per consecutive day (cap reached at 50 days)
    static final double STREAK_CAP = 0.5;    // streak bonus capped at +50%

    /**
     * @param difficulty 1..5 (clamped)
     * @param importance 1..5 (clamped)
     * @param streakDays current consecutive-day streak entering this check
     *                   (habit constance before increment; 0 for tasks / no streak)
     */
    public static double calculate(int difficulty, int importance, int streakDays) {
        int base = BASE_XP * (clamp(difficulty) + clamp(importance));
        double streakMultiplier = 1.0 + Math.min(Math.max(streakDays, 0) * STREAK_STEP, STREAK_CAP);
        return Math.round(base * streakMultiplier);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(5, value));
    }
}
