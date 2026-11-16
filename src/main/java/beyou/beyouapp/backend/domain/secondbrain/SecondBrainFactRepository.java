package beyou.beyouapp.backend.domain.secondbrain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SecondBrainFactRepository extends JpaRepository<SecondBrainFact, UUID> {

    /**
     * The fact, if it is this user's. Ownership is part of the lookup, so someone else's fact
     * reads exactly like one that does not exist.
     */
    Optional<SecondBrainFact> findByIdAndUserId(UUID id, UUID userId);

    /** The whole profile, oldest first. At most {@code SecondBrainFact.MAX_PER_USER} rows. */
    List<SecondBrainFact> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    long countByUserId(UUID userId);

    /**
     * Serialises the writes that decide something from everything the account already has: a
     * new fact (the limit) and a refill (the seeds, the daily check-in, the open-question
     * limit). The overview needs none: it is one upsert of one row.
     *
     * <p>Each of those reads and then writes what the read decided, and two running at once
     * would both read before either commits: the page mounting twice would seed every profile
     * question twice. An advisory lock, as on the Ideas board, because a new account has no row
     * to lock. Released at commit. {@code hashtext} can collide; two users sharing a key wait
     * for one short write, and nothing else.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    void lockProfile(@Param("key") String key);
}
