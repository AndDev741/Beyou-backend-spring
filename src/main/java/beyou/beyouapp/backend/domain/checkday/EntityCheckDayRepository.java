package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * The same window, narrowed to one account — the read behind
     * {@code GET /check-history}.
     *
     * <p>The user filter is part of the predicate rather than a check the caller runs
     * afterwards. An owner id belonging to somebody else simply matches nothing and the
     * endpoint reports the range as unknown, which leaks neither the row nor the fact that
     * the id exists. It also stays right when the habit is long deleted and only its
     * history survives (R8) — there is no entity left to ask "who owns this?", but the rows
     * still know which account they belong to.
     *
     * <p>Rides {@code uk_entity_check_day_owner_day} the same way the unscoped version
     * does; {@code user_id} is a filter on the handful of rows that come back, not a
     * leading column, so no new index is needed for it.
     */
    List<EntityCheckDay> findByUserIdAndOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
            UUID userId, CheckDayOwnerType ownerType, UUID ownerId, LocalDate from, LocalDate to);

    /**
     * One entity's entire history, oldest first — the read a recompute needs.
     *
     * <p>Deliberately unbounded. Every scalar the calculator derives is a function of the
     * whole history: the lifetime count, the first and last check-in dates, and a streak
     * walk whose stop condition is the earliest stored row. A windowed read would make the
     * count wrong the day the window slid past a check-in. Rides
     * {@code uk_entity_check_day_owner_day} as a prefix scan, so the cost is one row per day
     * the entity has existed — a few hundred for a habit held for a year.
     */
    List<EntityCheckDay> findByOwnerTypeAndOwnerIdOrderByDayAsc(
            CheckDayOwnerType ownerType, UUID ownerId);

    /**
     * Takes a transaction-scoped advisory lock on one owner (KTD26).
     *
     * <p>Not a row lock: the recompute reads a whole history and writes a scalar onto a
     * different table's row, so there is no single row to lock and {@code SELECT FOR UPDATE}
     * would not serialise the pair. {@code pg_advisory_xact_lock} blocks until it wins and
     * is released by the transaction ending, commit or rollback — nothing here can leak a
     * lock by forgetting to unlock.
     *
     * <p>Callers take the user's key first and the entity's second; see
     * {@code CheckDayRecorder.lockUserThenOwner} for why that order and not the other.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(CAST(:classId AS integer), CAST(:objectId AS integer))",
            nativeQuery = true)
    void lockCheckOwner(@Param("classId") int classId, @Param("objectId") int objectId);

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

    /**
     * Drops one day's row for one entity, returning the day to unknown (R18).
     *
     * <p>Used when a check is taken away while its day is still open. "Scheduled and left
     * unchecked" is only true once the day ends, so an open day carries no row at all
     * rather than a premature {@code MISSED}; the insert-only day-close pass stamps the
     * real outcome at close. Callers must be transactional.
     */
    @Modifying
    @Query("DELETE FROM EntityCheckDay e WHERE e.ownerType = :ownerType "
            + "AND e.ownerId = :ownerId AND e.day = :day")
    int deleteOwnerDay(CheckDayOwnerType ownerType, UUID ownerId, LocalDate day);
}
