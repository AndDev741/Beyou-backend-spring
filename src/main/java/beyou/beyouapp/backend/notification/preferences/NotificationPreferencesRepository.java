package beyou.beyouapp.backend.notification.preferences;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
