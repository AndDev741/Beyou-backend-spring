package beyou.beyouapp.backend.domain.common.DTO;

import java.util.UUID;

/**
 * One entity's post-mutation numbers, so the client can repaint it without a refetch.
 *
 * <p>The three check scalars are R21: a habit card shows its streak, its record and its
 * total, and the check response now carries all three rather than leaving the card to go
 * ask again. Owners that are earned into but never checked — categories — use the
 * five-argument constructor and leave them at zero.
 */
public record RefreshObjectDTO(
    UUID id,
    double xp,
    int level,
    double actualLevelXp,
    double nextLevelXp,
    int currentStreak,
    int bestStreak,
    int totalCheckIns
) {
    /** For owners with no check history of their own. */
    public RefreshObjectDTO(UUID id, double xp, int level, double actualLevelXp, double nextLevelXp) {
        this(id, xp, level, actualLevelXp, nextLevelXp, 0, 0, 0);
    }
}
