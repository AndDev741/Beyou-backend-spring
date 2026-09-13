package beyou.beyouapp.backend.domain.briefing.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Days older than yesterday that a retroactive check would still be accepted for.
 *
 * <p>{@code RoutineSnapshotScheduler.MAX_BACKFILL_DAYS} closes a day for good once it falls
 * out of the window, and {@code XpDecayCalculator} has already been reducing what a check
 * on it earns. That deadline is real and quantified, and until now it reached people only
 * through the engagement mail ({@code NudgeKind.XP_RECOVERY_WINDOW}). Surfacing it in the
 * dialog costs nothing and is the only thing in this feature with an actual clock on it.
 *
 * <p>Collapsed in the UI by default. The left panel is about yesterday; a list of seven
 * days every morning is how you teach somebody to close the dialog without reading it.
 *
 * @param oldestOpenDay      the earliest day still inside the window with something open
 * @param daysUntilExpiry    how many days remain before {@code oldestOpenDay} stops being
 *                           recoverable. One means tonight is the last chance
 * @param remainingXpPercent what a check on {@code oldestOpenDay} still pays, as a
 *                           percentage of the undecayed value, under this account's
 *                           strategy. Can be zero under {@code TIME_WINDOW}, which is worth
 *                           saying plainly rather than implying the XP is still there
 * @param openItems          everything still open on those older days, oldest first
 */
public record RecoveryWindow(
    LocalDate oldestOpenDay,
    long daysUntilExpiry,
    int remainingXpPercent,
    List<OpenItem> openItems
) {}
