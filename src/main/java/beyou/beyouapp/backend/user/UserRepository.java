package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.user.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}
