package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * Reads and deletes over the per-day outcome history.
 *
 * <p>Every method here is shaped by an index declared on {@link EntityCheckDay}:
 * the owner queries ride {@code uk_entity_check_day_owner_day}, the user
 * queries ride {@code idx_entity_check_day_user_day}. Adding a query with a
 * different leading column means adding an index to match.
 */
public interface EntityCheckDayRepository extends JpaRepository<EntityCheckDay, UUID> {

    /**
     * One entity's history over a window, oldest first — the shape a habit's
     * or routine's history endpoint returns. Both bounds are inclusive.
     */
    List<EntityCheckDay> findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
            CheckDayOwnerType ownerType, UUID ownerId, LocalDate from, LocalDate to);

    /**
     * Everything already recorded for one user on one day.
     *
     * <p>Used when closing a day: the writer knows every owner that should have
     * a row, reads what is already there, and only inserts the absences that
     * are missing. Without the diff a second pass over the same day would
     * collide with {@code uk_entity_check_day_owner_day}.
     */
    List<EntityCheckDay> findByUserIdAndDay(UUID userId, LocalDate day);

    /**
     * One user's whole history over a window, every owner type together, oldest
     * first. This is the export read. Both bounds are inclusive.
     */
    List<EntityCheckDay> findByUserIdAndDayBetweenOrderByDayAsc(
            UUID userId, LocalDate from, LocalDate to);

    /**
     * Drops one entity's entire history, for the habit/task delete path (R8).
     *
     * <p>A bulk delete rather than the derived {@code deleteBy...}, which loads
     * every row into the persistence context and issues one DELETE each — for a
     * habit held for a couple of years that is hundreds of statements to
     * accomplish one. Returns the number of rows removed so the caller can log
     * it. Callers must be transactional; nothing here is flushed to the
     * persistence context, so a caller holding these entities should clear it.
     */
    @Modifying
    @Query("DELETE FROM EntityCheckDay e WHERE e.ownerType = :ownerType AND e.ownerId = :ownerId")
    int deleteAllByOwner(CheckDayOwnerType ownerType, UUID ownerId);
}
