package beyou.beyouapp.backend.domain.briefing.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A goal worth mentioning this morning, and how its pace looks.
 *
 * <p>{@code percentComplete} and {@code daysRemaining} are computed on the server and not
 * left to either client, for the reason the whole facts/prose split exists: two clients
 * drift, and a percentage that disagrees between the phone and the web is the kind of bug
 * nobody reports and everybody notices. The pace fields follow the same rule.
 *
 * @param daysRemaining   whole days from the account's today to {@code endDate}. Zero means
 *                        the goal ends today; negative means it is already past due, which
 *                        is worth saying rather than hiding
 * @param remainingValue  how far the current value is from the target, never negative
 * @param requiredPerDay  what each day from today through the end date has to add to land
 *                        on the target, counting today. Null once the goal is overdue or
 *                        the target is already met, because neither has a daily rate to ask
 *                        for
 * @param expectedPercent where a straight line from the start date to the end date says the
 *                        goal should be by today, 0 to 100
 * @param pace            the verdict the clients render, see {@link GoalPace}
 */
public record GoalAhead(
    UUID id,
    String name,
    String iconId,
    double currentValue,
    double targetValue,
    String unit,
    LocalDate endDate,
    long daysRemaining,
    int percentComplete,
    double remainingValue,
    Double requiredPerDay,
    int expectedPercent,
    GoalPace pace
) {}
