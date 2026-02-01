package beyou.beyouapp.backend.security.RefreshToken;

import java.sql.Timestamp;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table
@Getter
@Setter
public class RefreshToken {
    
    @Id
    @GeneratedValue
    @Column(updatable = false, nullable = false, unique = true)
    UUID id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    String tokenHash;

    Timestamp expiresAt;

    Timestamp revokedAt;

    Timestamp createdAt;
    
}
