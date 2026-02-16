package beyou.beyouapp.backend.security.RefreshToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String hashCode);
    List<RefreshToken> findAllByUserId(UUID userId);
}
