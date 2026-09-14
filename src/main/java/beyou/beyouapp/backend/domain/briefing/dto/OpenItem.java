package beyou.beyouapp.backend.domain.briefing.dto;

import java.time.LocalDate;
import java.util.UUID;

import beyou.beyouapp.backend.domain.routine.snapshot.SnapshotItemType;

/**
 * One thing a past day is still waiting on.
 *
 * <p>Carries {@code snapshotId} and {@code snapshotCheckId} because those are exactly the
 * two values {@code POST /routine/snapshot/check} and {@code /skip} take. The dialog acts
 * through the endpoints that already exist, which is what keeps the retroactive rules — XP
 * decay, the outcome stamped on the snapshot's own date, the ownership check — in the one
 * service that already enforces them rather than copied into a briefing-shaped variant.
 *
 * @param xpIfCheckedNow what checking this right now would actually pay, after the
 *                       account's {@code XpDecayStrategy} has been applied. Shown in the
 *                       UI on purpose: a user who checks something and watches a smaller
 *                       number land with no explanation reads it as a bug
 */
public record OpenItem(
    UUID snapshotId,
    UUID snapshotCheckId,
    LocalDate date,
    UUID routineId,
    String routineName,
    SnapshotItemType itemType,
    String itemName,
    String itemIconId,
    String sectionName,
    double xpIfCheckedNow
) {}
