package beyou.beyouapp.backend.notification.engagement;

import java.time.LocalDate;
import java.util.Optional;

/**
 * What, if anything, to send one account today — and the numbers the mail needs to say
 * something concrete.
 *
 * <p>A record rather than a bare {@link NudgeKind} because a nudge with no numbers in it
 * is a nudge nobody acts on. "Your streak is at risk" is a horoscope; "you are one day
 * from your record of 23, and today is a scheduled day" is a reason to open the app. Every
 * field here exists to end up in the message.
 */
public record NudgeDecision(
        NudgeKind kind,

        /**
         * XP_RECOVERY_WINDOW: the day that stops being recoverable after today.
         * Null for every other kind.
         */
        LocalDate expiringDay,

        /**
         * XP_RECOVERY_WINDOW: what a check on {@link #expiringDay} still earns, as a
         * percentage of the full value, per the account's own decay strategy. Reported
         * rather than assumed, because the three strategies pay differently and a mail
         * quoting the wrong one is worse than a mail quoting nothing.
         */
        int remainingXpPercent,

        /** STREAK_RECORD_AT_RISK: the run standing right now. */
        int currentStreak,

        /** STREAK_RECORD_AT_RISK: the account's own record, which is what is at stake. */
        int bestStreak
) {

    /** No nudge for this account today. Not an error — the common case, by design. */
    public static Optional<NudgeDecision> none() {
        return Optional.empty();
    }

    public static NudgeDecision xpRecoveryWindow(LocalDate expiringDay, int remainingXpPercent) {
        return new NudgeDecision(NudgeKind.XP_RECOVERY_WINDOW, expiringDay, remainingXpPercent, 0, 0);
    }

    public static NudgeDecision streakRecordAtRisk(int currentStreak, int bestStreak) {
        return new NudgeDecision(NudgeKind.STREAK_RECORD_AT_RISK, null, 0, currentStreak, bestStreak);
    }

    /**
     * True when the run standing now would beat the record by finishing today. The mail
     * reads differently in that case — it is a record to set rather than one to protect —
     * and the distinction is cheap to carry and impossible to recover later.
     */
    public boolean isRecordWithinReach() {
        return kind == NudgeKind.STREAK_RECORD_AT_RISK && currentStreak >= bestStreak;
    }
}
