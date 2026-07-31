package beyou.beyouapp.backend.security.RefreshToken;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    List<RefreshToken> findAllByUserId(UUID userId);

    /**
     * R21 — removes an account's refresh tokens outright.
     *
     * {@code refresh_tokens.user_id} is a plain foreign key with no
     * {@code ON DELETE CASCADE} (V1__baseline.sql) and {@code User} maps no
     * {@code @OneToMany} for it, so nothing clears these rows on its own.
     * Every account that has ever logged in holds at least one, and each one
     * blocks the account delete. Bulk-deleted rather than loaded first: the
     * rows are about to stop existing, so hydrating them buys nothing.
     */
    @Modifying
    @Query("DELETE FROM RefreshToken rt WHERE rt.user.id = :userId")
    int deleteAllByUserId(UUID userId);
}
