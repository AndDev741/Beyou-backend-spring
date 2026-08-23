package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.user.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);

    Optional<User> findByVerificationToken(String verificationToken);

    @Query("SELECT DISTINCT u.timezone FROM User u")
    List<String> findDistinctTimezones();

    List<User> findAllByTimezone(String timezone);

    /**
     * Addresses of everyone holding a role, for the feedback inbox alert.
     *
     * Returns the column rather than the entity on purpose: the caller only
     * ever mails these, and loading whole User rows to read one field pulls
     * the whole profile — photo metadata included — into memory for nothing.
     *
     * Rows whose role is null never match, which is the wanted answer: the
     * role is only ever ADMIN by a hand-written UPDATE.
     */
    @Query("SELECT u.email FROM User u WHERE u.userRole = :role")
    List<String> findEmailsByUserRole(@Param("role") UserRole role);

    /**
     * Native on purpose: {@code last_login_at} / {@code last_seen_at} exist only in the
     * database (V22), never on the entity — see the migration header for why. Each method
     * opens its own transaction so {@link beyou.beyouapp.backend.monitoring.UserActivityTracker}
     * can catch a failure OUTSIDE the transactional proxy and let the request proceed;
     * a {@code @Transactional} wrapper up there that swallowed the exception would commit
     * a rollback-marked transaction and fail the request it was trying not to fail.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET last_login_at = :at, last_seen_at = :at WHERE id = :id", nativeQuery = true)
    void recordLogin(@Param("id") UUID id, @Param("at") Instant at);

    @Modifying
    @Transactional
    @Query(value = "UPDATE users SET last_seen_at = :at WHERE id = :id", nativeQuery = true)
    void recordSeen(@Param("id") UUID id, @Param("at") Instant at);

    /**
     * Gives back the resend cooldown of a user whose verification mail failed to send.
     *
     * <p>An update rather than a load-and-save because the caller runs in an
     * {@code afterCommit} callback with its own short transaction
     * ({@link beyou.beyouapp.backend.user.verification.EmailVerificationWrites}), and
     * reading the whole row back to null one column would drag the profile with it.
     */
    @Modifying
    @Query("UPDATE User u SET u.verificationTokenSentAt = null WHERE u.id = :id")
    void clearVerificationTokenSentAt(@Param("id") UUID id);
}
