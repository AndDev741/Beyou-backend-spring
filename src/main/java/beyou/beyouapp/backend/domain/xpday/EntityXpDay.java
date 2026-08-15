package beyou.beyouapp.backend.domain.xpday;

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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What one entity's XP did on one day.
 *
 * <p>The totals on {@code XpProgress} answer "where does this stand"; nothing answered
 * "how did it get here". This does, at the only grain the question is ever asked in: a
 * day. The widgets in the redesign draw a week of bars from it, and the category cards
 * the same series.
 *
 * <p>{@link #xp} is a NET delta, so it can be negative. Unchecking a habit gives its XP
 * back, and the day's bar has to shrink rather than remember a high-water mark. Summing
 * every row for an owner reproduces its total, which is the property that makes the two
 * checkable against each other.
 *
 * <p>{@link #ownerId} is a bare {@code UUID} with no foreign key, modelled on
 * {@code EntityCheckDay.ownerId} and for the reasons documented there: it is
 * polymorphic, so no single key can point at it. Deleting the entity clears its series
 * through an explicit delete, not the database's choice.
 *
 * <p>{@code user_id} IS a real association with {@code ON DELETE CASCADE} — account
 * deletion has to take this table with it rather than be blocked by it. See the
 * delete-ordering note on {@code UserService.deleteUser}.
 *
 * <p>Schema lives in {@code V19__entity_xp_day.sql}. Hibernate runs
 * {@code ddl-auto: validate} everywhere, so the two cannot drift silently.
 */
@Entity
@Table(
        name = "entity_xp_day",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_entity_xp_day_owner_day",
                        columnNames = {"owner_type", "owner_id", "day"})
        },
        indexes = {
                @Index(name = "idx_entity_xp_day_user_day", columnList = "user_id, day")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EntityXpDay {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 16)
    private XpDayOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Column(name = "xp", nullable = false)
    private double xp;
}
