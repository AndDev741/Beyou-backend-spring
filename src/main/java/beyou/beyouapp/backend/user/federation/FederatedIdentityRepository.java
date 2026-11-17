package beyou.beyouapp.backend.user.federation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * The provider refresh tokens kept for revocation (Sign in with Apple), read as plain
     * values on purpose. Account deletion calls this right before deleting the user, and a
     * loaded {@link FederatedIdentity} would sit MANAGED in the session pointing at that
     * user, which Hibernate refuses to flush (TransientPropertyValueException).
     */
    @Query("select new beyou.beyouapp.backend.user.federation.ProviderToken(f.providerRefreshToken, f.providerClientId) "
            + "from FederatedIdentity f where f.user.id = :userId and f.issuer = :issuer "
            + "and f.providerRefreshToken is not null and f.providerClientId is not null")
    List<ProviderToken> findProviderTokens(@Param("userId") UUID userId, @Param("issuer") String issuer);
}
