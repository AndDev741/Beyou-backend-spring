package beyou.beyouapp.backend.notification.preferences;

import java.time.Instant;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Whether this account may be sent engagement mail, and the token that turns it off
 * from inside a message.
 *
 * <p>One row per user, keyed by the user's own id — see {@code V24} for why this is a
 * table of its own rather than two columns on {@link User}, and why the token is stored
 * raw where every other token in this codebase is hashed.
 *
 * <p><b>Absence means the default.</b> No row is written until something needs one, so
 * every reader has to treat a missing row as "opted in, no token yet" rather than as
 * "opted out". {@link NotificationPreferencesService} is the only place that decision
 * lives; nothing else should query this repository directly.
 */
@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class NotificationPreferences {

    /**
     * The owning account's id, which is also this row's primary key. {@link MapsId}
     * derives it from {@link #user} so the two can never disagree — a surrogate id
     * beside a unique constraint would allow exactly that.
     */
    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /**
     * LAZY, and it matters more here than it looks. This entity is reached from the
     * nightly nudge pass, which walks a page of accounts at a time; an EAGER
     * association would load the whole {@link User} graph per row to answer a question
     * about one boolean.
     */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    private User user;

    @Column(name = "engagement_email", nullable = false)
    private boolean engagementEmail = true;

    /**
     * The unguessable value an unsubscribe link carries. Never logged, and never sent
     * to a client that did not already present it: it is a capability, so anything that
     * echoes it widens who can use it.
     */
    @Column(name = "unsubscribe_token", nullable = false, length = 64)
    @ToString.Exclude
    private String unsubscribeToken;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
