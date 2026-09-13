package beyou.beyouapp.backend.domain.briefing;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyBriefingRepository extends JpaRepository<DailyBriefing, UUID> {

    /** Backed by {@code daily_briefing_user_day_key}. */
    Optional<DailyBriefing> findByUserIdAndBriefingDate(UUID userId, LocalDate briefingDate);

    /**
     * Drops briefings older than {@code cutoff}, across every account.
     *
     * <p>One row per user per day accumulates forever otherwise, and nothing reads a
     * briefing after its own day is over — the dialog only ever asks about today, and the
     * facts it shows are recomputed anyway. Deleting by date alone rather than per user
     * because the sweep has no user to anchor on; {@code idx_daily_briefing_date} exists
     * for exactly this statement.
     */
    @Modifying
    @Query("DELETE FROM DailyBriefing b WHERE b.briefingDate < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDate cutoff);
}
