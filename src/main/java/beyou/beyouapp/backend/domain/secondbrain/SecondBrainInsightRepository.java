package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SecondBrainInsightRepository extends JpaRepository<SecondBrainInsight, UUID> {

    /**
     * Replaces the account's overview, creating the row the first time. One statement, for the
     * reason given on {@link SecondBrainSourceSettingRepository#upsert}: two refreshes of an
     * account with no row must not end in a primary-key violation.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO second_brain_insights (user_id, text, verdict, generated_at)
            VALUES (:userId, :text, :verdict, :generatedAt)
            ON CONFLICT (user_id) DO UPDATE
                SET text = EXCLUDED.text,
                    verdict = EXCLUDED.verdict,
                    generated_at = EXCLUDED.generated_at
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId,
                @Param("text") String text,
                @Param("verdict") String verdict,
                @Param("generatedAt") Instant generatedAt);
}
