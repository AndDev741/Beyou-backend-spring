package beyou.beyouapp.backend.domain.xpday.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import beyou.beyouapp.backend.domain.xpday.XpDayOwnerType;

/**
 * One user's XP history over a window, ready to be drawn.
 *
 * <p>{@code days} is the x axis, sent once rather than repeated inside every series:
 * every {@code values} array lines up with it index for index. That is what lets a
 * client draw a bar per day without parsing a date, and what keeps a week of forty
 * categories from carrying forty copies of the same seven dates.
 *
 * @param from   first day of the window, in the account's timezone
 * @param to     last day, which is the account's today
 * @param days   every day in between, ascending, gaps included
 * @param series one entry per entity that has any history in the window
 */
public record XpHistoryResponseDTO(
        LocalDate from,
        LocalDate to,
        List<LocalDate> days,
        List<OwnerSeries> series) {

    /**
     * @param values XP for each day of {@code days}, in the same order. Zero where
     *               nothing happened, and negative where XP was given back.
     */
    public record OwnerSeries(XpDayOwnerType ownerType, UUID ownerId, List<Double> values) {}
}
