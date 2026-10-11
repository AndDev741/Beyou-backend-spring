package beyou.beyouapp.backend.notification.preferences;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reads of {@link NotificationPreferences}. Go through
 * {@link NotificationPreferencesService} instead of calling this directly: a missing row
 * means "opted in, no token yet", and that rule has to live in exactly one place.
 */
public interface NotificationPreferencesRepository extends JpaRepository<NotificationPreferences, UUID> {

    /**
     * The lookup an unsubscribe link performs. Hits the unique index from {@code V24};
     * an unknown token finds nothing, which is all the caller is told.
     */
    Optional<NotificationPreferences> findByUnsubscribeToken(String unsubscribeToken);

    /**
     * Writes the default row unless the account already has one, and never raises on the
     * collision. The reminder columns take their defaults from V40.
     *
     * <p>Native because the point is {@code ON CONFLICT DO NOTHING}: a primary-key violation
     * raised inside a JPA transaction marks it rollback-only, so a request that lost the
     * first-read race could not recover even after re-reading the winner's row.
     *
     * @return 1 when this call created the row, 0 when another request had already
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_preferences (user_id, engagement_email, unsubscribe_token, created_at, updated_at)
            VALUES (:userId, true, :token, now(), now())
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int insertDefaultsIfAbsent(@Param("userId") UUID userId, @Param("token") String token);
}
