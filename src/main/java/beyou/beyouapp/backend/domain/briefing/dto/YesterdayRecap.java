package beyou.beyouapp.backend.domain.briefing.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The day that just ended, and what can still be done about it.
 *
 * <p>Read entirely from that day's {@code RoutineSnapshot} rows rather than from the live
 * routine. After the midnight pass {@code SnapshotCheckMigrator} deletes the live checks
 * for the date, so the snapshot is not merely the convenient source — it is the only one.
 *
 * @param date        the day being described, the account's own
 * @param hadRoutine  whether any routine covered it. False means nothing was asked of the
 *                    user, which is not the same as a day they ignored, and the clients
 *                    must not render it as a failure
 * @param complete    every snapshot of the day finished, under whichever
 *                    {@code ConstanceConfiguration} the account uses. Already decided by
 *                    {@code SnapshotCheckService}; nothing here re-derives it
 * @param doneCount   items checked, however late
 * @param skippedCount items deliberately skipped. Not failures: a skip keeps the day out of
 *                    the MISSED column and the streak walks straight through it
 * @param xpEarned    XP the day actually paid out, decay included
 * @param openItems   what is still neither checked nor skipped, and therefore still
 *                    actionable from the dialog. This is the list the left panel renders
 * @param focusCycles pomodoros run on the day, counted across every routine
 * @param moodLevel   the 1-5 mood logged for the day, or null if none was
 */
public record YesterdayRecap(
    LocalDate date,
    boolean hadRoutine,
    boolean complete,
    int doneCount,
    int skippedCount,
    double xpEarned,
    List<OpenItem> openItems,
    int focusCycles,
    Integer moodLevel
) {

    /** A day with a routine on it and nothing left hanging. */
    public boolean fullyResolved() {
        return hadRoutine && openItems.isEmpty();
    }
}
