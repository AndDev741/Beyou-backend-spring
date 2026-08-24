package beyou.beyouapp.backend.notification.engagement;

/**
 * The engagement nudges that exist.
 *
 * <p><b>Adding a value here is not enough.</b> {@code notification_sends.kind} carries a
 * CHECK constraint listing these names, so a new value needs a migration alongside it —
 * otherwise every send of the new kind fails on insert, at send time, inside a scheduled
 * job nobody is watching.
 *
 * <p>The name is stored, not the ordinal, so these may be reordered but not renamed: a
 * rename orphans every row already written and silently re-opens the per-day dedupe for
 * accounts that already received the mail.
 */
public enum NudgeKind {

    /**
     * The oldest day still open for a retroactive check is about to fall out of the
     * backfill window.
     *
     * <p>This is the one nudge in the product with a real, quantified deadline behind it
     * rather than an invented one: {@code XpDecayCalculator} already reduces what a late
     * check earns, and {@code RoutineSnapshotScheduler.MAX_BACKFILL_DAYS} closes the day
     * for good. The mail reports a cost that exists whether or not anybody is told about
     * it.
     */
    XP_RECOVERY_WINDOW,

    /**
     * A streak that is at or near the account's own record has something scheduled today
     * and nothing checked yet.
     *
     * <p>Deliberately not "you have not checked anything today", which is the same query
     * with none of the meaning: for an account with no run going, that mail is a
     * reprimand for a day that is still in progress. Tied to the record, it is a warning
     * about losing something the person actually built.
     */
    STREAK_RECORD_AT_RISK
}
