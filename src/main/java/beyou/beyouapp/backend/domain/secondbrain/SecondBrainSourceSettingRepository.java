package beyou.beyouapp.backend.domain.secondbrain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The switches a person has touched. Go through {@link SecondBrainStore}: a missing row means
 * the source's default, and that rule has to live in one place.
 */
@Repository
public interface SecondBrainSourceSettingRepository
        extends JpaRepository<SecondBrainSourceSetting, SecondBrainSourceSetting.Key> {

    List<SecondBrainSourceSetting> findByUserId(UUID userId);

    /**
     * Sets one switch, creating its row the first time.
     *
     * <p>Native {@code ON CONFLICT}, the idea {@code NotificationPreferencesRepository} uses:
     * two toggles of a switch with no row yet would both miss a JPA read and both insert, and
     * the loser's primary-key violation marks its transaction rollback-only. Postgres settling
     * the collision itself leaves nothing to recover from.
     *
     * <p>Clears the persistence context so a read later in the same session cannot return a
     * setting loaded before this write.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO second_brain_sources (user_id, source, enabled, updated_at)
            VALUES (:userId, :source, :enabled, :now)
            ON CONFLICT (user_id, source) DO UPDATE
                SET enabled = EXCLUDED.enabled,
                    updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId,
                @Param("source") String source,
                @Param("enabled") boolean enabled,
                @Param("now") Instant now);
}
