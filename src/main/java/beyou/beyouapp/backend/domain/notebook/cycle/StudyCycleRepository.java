package beyou.beyouapp.backend.domain.notebook.cycle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

@Repository
public interface StudyCycleRepository extends JpaRepository<StudyCycle, UUID> {

    /** The user's active cycles, in display order. */
    @Query("select c from StudyCycle c where c.user.id = :userId and c.archivedAt is null "
            + "order by c.position asc, c.createdAt asc")
    List<StudyCycle> findActive(@Param("userId") UUID userId);

    long countByUserIdAndArchivedAtIsNull(UUID userId);

    /**
     * The row, locked until the transaction ends. Every write that moves a lap takes this first,
     * so two pomodoros finishing together cannot both close the same lap, or both credit the same
     * block from the same starting minutes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StudyCycle c where c.id = :id")
    Optional<StudyCycle> lockById(@Param("id") UUID id);

    /**
     * Every active cycle of the user, locked, in id order. A page finishing can close blocks in
     * several cycles at once; taking the locks in one fixed order is what keeps two such writers
     * from deadlocking on each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StudyCycle c where c.user.id = :userId and c.archivedAt is null order by c.id")
    List<StudyCycle> lockActive(@Param("userId") UUID userId);

    /**
     * Serialises the writes that choose a user's subjects (create and configuration update).
     *
     * <p>Whether a new subject overlaps one of another cycle is a read of every active cycle's
     * subjects followed by a write the read decided on, and two of those running at once would
     * both read before either commits. The partial unique index catches the same page twice, not
     * a page inside another. Advisory rather than {@link #lockActive}, because a user with no
     * active cycle yet has no row to lock. Taken before any cycle row lock, and the credit paths
     * never take it, so the two kinds of lock cannot wait on each other in opposite orders.
     * {@code hashtext} can collide; two users sharing a key wait for one save, and nothing else.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    void lockSubjects(@Param("key") String key);

    /** Every cycle the user ever made, archived ones included, for the data export. */
    @Query("select c from StudyCycle c where c.user.id = :userId order by c.createdAt asc")
    List<StudyCycle> findAllForExport(@Param("userId") UUID userId);
}
