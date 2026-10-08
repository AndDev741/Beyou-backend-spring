package beyou.beyouapp.backend.domain.briefing.dto;

/**
 * Where a goal stands against a straight line from its start date to its end date.
 *
 * <p>Decided on the server for the same reason the percentage is: two clients would each
 * pick their own tolerance and disagree about whether the same goal is behind. The straight
 * line is a deliberate simplification. Nobody progresses evenly, but "you are a third of the
 * way through the time and a tenth of the way to the target" is the honest question a
 * morning panel can ask, and it needs no history to answer.
 */
public enum GoalPace {

    /** Progress is at or near where the straight line says it should be by today. */
    ON_TRACK,

    /** Progress trails the straight line by more than the tolerance. */
    BEHIND,

    /** The end date has passed and the target was not reached. */
    OVERDUE,

    /**
     * The target is met but the goal is still open. Worth its own state: the XP for a goal
     * is paid by {@code PUT /goal/complete}, not by reaching the number, so a goal sitting
     * here is a reward the user has earned and not collected.
     */
    REACHED
}
