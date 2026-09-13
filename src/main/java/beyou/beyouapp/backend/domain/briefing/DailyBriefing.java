package beyou.beyouapp.backend.domain.briefing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * The cached half of one day's briefing.
 *
 * <p>Only the generated prose and the "was this seen" marker live here. The facts the
 * dialog shows — yesterday's open items, the goals coming due, where the streak stands —
 * are recomputed on every read by {@link DailyBriefingFactsBuilder}, because the user can
 * change them from inside the dialog itself. Caching them would need an invalidation hook
 * on every check path, which is a worse trade than three indexed queries. See
 * {@code V32__daily_briefing.sql} for the longer version of that argument.
 *
 * <p>{@code seenAt} is deliberately server-side rather than in each client's local storage.
 * Somebody who closes yesterday's loose ends on their phone and then opens the web would
 * otherwise be asked the same questions twice, which is exactly the annoyance the dialog
 * exists to remove.
 */
@Entity
@Table(
    name = "daily_briefing",
    uniqueConstraints = @UniqueConstraint(
        name = "daily_briefing_user_day_key", columnNames = {"user_id", "briefing_date"}),
    indexes = @Index(name = "idx_daily_briefing_date", columnList = "briefing_date")
)
@Getter
@Setter
@NoArgsConstructor
public class DailyBriefing {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** The account's own day, resolved through {@code UserDateResolver} (R15). */
    @Column(name = "briefing_date", nullable = false)
    private LocalDate briefingDate;

    /**
     * The generated lines as JSON, or null while nothing has been generated.
     *
     * <p>Serialized rather than mapped to columns because it is two short lists of prose
     * whose shape belongs to the clients, not to the schema. A column per line would have
     * to be widened every time the carousel grows a page.
     */
    @Column(name = "narrative_json", columnDefinition = "text")
    private String narrativeJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "narrative_status", nullable = false, length = 16)
    private NarrativeStatus narrativeStatus = NarrativeStatus.PENDING;

    @Column(name = "seen_at")
    private Instant seenAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public DailyBriefing(User user, LocalDate briefingDate) {
        this.user = user;
        this.briefingDate = briefingDate;
        this.narrativeStatus = NarrativeStatus.PENDING;
        this.createdAt = Instant.now();
    }
}
