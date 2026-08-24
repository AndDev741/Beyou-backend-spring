package beyou.beyouapp.backend.notification.engagement;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A record that one engagement mail went out — the thing that stops it going out twice.
 *
 * <p>Written only after the send is handed to the mail layer, never before: a row written
 * ahead of a failed send silently suppresses the nudge for the rest of the day, which is
 * the failure mode nobody notices. See {@code EngagementNudgeScheduler} for the ordering.
 */
@Entity
@Table(name = "notification_sends")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class NotificationSend {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * LAZY: the nudge pass counts and inserts these rows in bulk and never reads the
     * account off them, so an EAGER association would load a User graph per row.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private NudgeKind kind;

    /**
     * The recipient's local date, not the server's — see V25. "Already sent today" has to
     * mean their today, or an account far enough east receives the same nudge twice
     * across one of its own days.
     */
    @Column(name = "sent_on", nullable = false)
    private LocalDate sentOn;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public NotificationSend(User user, NudgeKind kind, LocalDate sentOn) {
        this.user = user;
        this.kind = kind;
        this.sentOn = sentOn;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
