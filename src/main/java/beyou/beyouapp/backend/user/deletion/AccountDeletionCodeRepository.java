package beyou.beyouapp.backend.user.deletion;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountDeletionCodeRepository extends JpaRepository<AccountDeletionCode, UUID> {

    Optional<AccountDeletionCode> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("update AccountDeletionCode c set c.usedAt = :usedAt "
            + "where c.user.id = :userId and c.usedAt is null and c.expiresAt > :now")
    int invalidateActiveCodes(@Param("userId") UUID userId,
            @Param("usedAt") Timestamp usedAt,
            @Param("now") Timestamp now);

    /**
     * Counts one wrong guess, in SQL rather than read-modify-write so two guesses
     * racing each other still count as two. Called only through
     * {@link AccountDeletionCodeWrites}, which owns the transaction it needs.
     */
    @Modifying
    @Query("update AccountDeletionCode c set c.attempts = c.attempts + 1 where c.id = :id")
    int recordFailedAttempt(@Param("id") UUID id);

}
