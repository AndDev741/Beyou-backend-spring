package beyou.beyouapp.backend.notification.engagement;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The three reads the send budget is made of. Each answers one of the limits V25
 * describes, and they are separate queries because they are separate limits.
 */
public interface NotificationSendRepository extends JpaRepository<NotificationSend, UUID> {

    /**
     * Limit 1 — this account, this nudge, this local day. The unique constraint is what
     * actually enforces it; this read is the cheap path that avoids relying on a caught
     * constraint violation for the common case.
     */
    boolean existsByUserIdAndKindAndSentOn(UUID userId, NudgeKind kind, LocalDate sentOn);

    /**
     * Limit 2 — the most recent day this account was mailed anything, regardless of kind.
     * Two triggers can each be individually justified on the same morning; their sum is
     * not, and only a cross-kind read can see that.
     */
    @Query("SELECT MAX(s.sentOn) FROM NotificationSend s WHERE s.user.id = :userId")
    Optional<LocalDate> findLastSentOn(@Param("userId") UUID userId);

    /**
     * Limit 3 — how many went out on this date across every account, for the daily cap
     * that keeps the nudges from spending the transactional mail budget.
     *
     * <p>Counts by the recipients' local dates, so around a date boundary this spans two
     * of them and the cap is approximate at the edges. Deliberate: the alternative is a
     * server-day column that makes limit 1 wrong, and being a few mails out on a budget
     * of hundreds is a far smaller error than mailing somebody twice.
     */
    long countBySentOn(LocalDate sentOn);
}
