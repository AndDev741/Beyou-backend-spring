package beyou.beyouapp.backend.user.federation;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One external identity a beyou account may be entered through.
 *
 * <p>The identity is the pair {@code (issuer, subject)} and nothing else. Both come
 * from a verified ID token, both are assigned by the issuer, and neither can be
 * reassigned to a different person by anyone but that issuer. {@code emailAtLink}
 * is a record of what was claimed, never a way to find a user — see V29's header
 * for why that distinction is the whole reason this table exists.
 */
@Entity
@Table(name = "federated_identities")
@AllArgsConstructor
@NoArgsConstructor
@Setter
@Getter
public class FederatedIdentity {

    @Id
    @UuidGenerator
    @Column(updatable = false, nullable = false, unique = true)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The {@code iss} claim, verbatim. Compared byte-for-byte, never normalised. */
    @Column(nullable = false, length = 255)
    private String issuer;

    /** The {@code sub} claim, verbatim. Unique per user within one issuer, forever. */
    @Column(nullable = false, length = 255)
    private String subject;

    /**
     * What the issuer claimed the address was when the link was made.
     *
     * <p>Deliberately not kept in step with the account's own email, and deliberately
     * never queried. It answers "which account is this?" in support, and "did the
     * claimed address change since?" in an audit. Reading it to resolve a login would
     * re-open exactly the hole this table closes.
     */
    @Column(name = "email_at_link", length = 320)
    private String emailAtLink;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    public FederatedIdentity(User user, FederatedPrincipal principal, LocalDateTime now) {
        this.user = user;
        this.issuer = principal.issuer();
        this.subject = principal.subject();
        this.emailAtLink = principal.email();
        this.createdAt = now;
        this.lastLoginAt = now;
    }
}
