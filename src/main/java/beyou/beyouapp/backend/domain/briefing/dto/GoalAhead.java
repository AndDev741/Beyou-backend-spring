package beyou.beyouapp.backend.domain.briefing.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A goal close enough to its end date to be worth mentioning this morning.
 *
 * <p>{@code percentComplete} and {@code daysRemaining} are computed on the server and not
 * left to either client, for the reason the whole facts/prose split exists: two clients
 * drift, and a percentage that disagrees between the phone and the web is the kind of bug
 * nobody reports and everybody notices.
 *
 * @param daysRemaining whole days from the account's today to {@code endDate}. Zero means
 *                      the goal ends today; negative means it is already past due, which
 *                      is worth saying rather than hiding
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
    int percentComplete
) {}
