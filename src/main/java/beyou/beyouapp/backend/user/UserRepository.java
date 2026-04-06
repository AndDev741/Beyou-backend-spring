package beyou.beyouapp.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
