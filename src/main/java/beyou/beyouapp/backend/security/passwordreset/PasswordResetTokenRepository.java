package beyou.beyouapp.backend.security.passwordreset;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {
    Optional<PasswordResetToken> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :usedAt where t.user.id = :userId and t.usedAt is null and t.expiresAt > :now")
    int invalidateActiveTokens(@Param("userId") UUID userId, @Param("usedAt") Timestamp usedAt, @Param("now") Timestamp now);

    /**
     * Clears the rows out of the way of an account delete: `password_reset_tokens.user_id`
     * is a plain foreign key, so a user who has ever asked for a reset cannot be
     * deleted while its tokens stand.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.user.id = :userId")
    int deleteAllForUser(@Param("userId") UUID userId);
}
