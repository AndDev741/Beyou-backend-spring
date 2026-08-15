package beyou.beyouapp.backend.user.deletion;

import java.sql.Timestamp;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A six-digit code, mailed to the account's address, that has to come back before
 * the account is deleted. Stored as a BCrypt hash like a password reset token: a
 * leaked database row must not let anyone finish someone else's deletion.
 */
@Entity
@Table(name = "account_deletion_codes")
@Getter
@Setter
public class AccountDeletionCode {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String codeHash;

    /** Six digits is a million guesses, so a code dies after a few wrong ones. */
    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private Timestamp createdAt;

    @Column(nullable = false)
    private Timestamp expiresAt;

    private Timestamp usedAt;
}
