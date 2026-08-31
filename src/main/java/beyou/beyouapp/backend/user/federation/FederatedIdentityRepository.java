package beyou.beyouapp.backend.user.federation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FederatedIdentityRepository extends JpaRepository<FederatedIdentity, UUID> {

    /**
     * The only lookup that may resolve a login. There is deliberately no
     * {@code findByEmailAtLink}: adding one would make the claimed address an identity
     * again, which is the bug V29 exists to prevent.
     */
    Optional<FederatedIdentity> findByIssuerAndSubject(String issuer, String subject);

    /** The settings screen, and the guard that refuses to unlink the last way in. */
    List<FederatedIdentity> findAllByUserId(UUID userId);

    boolean existsByUserIdAndIssuer(UUID userId, String issuer);
}
