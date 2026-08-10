package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
import lombok.ToString;

/**
 * R5/R6 — one row per checkable entity per day, carrying exactly one outcome,
 * kept indefinitely.
 *
 * <p>This is the side table half of KTD2. The rolling scalars live on the
 * entity itself ({@code CheckProgress}); the history that grows by one row per
 * entity per day lives here, where it is only read by the queries that ask for
 * it. Mapping it onto {@code User} would put it in the path of
 * {@code SecurityFilter}, which loads the full user on every authenticated
 * request.
 *
 * <p>{@link #ownerId} is a bare {@code UUID} with no foreign key, modelled on
 * {@code SnapshotCheck.originalItemId}. R8 wants a day's history to outlive the
 * routine it was recorded through, and routine edits to leave it alone — a
 * foreign key would either block those deletes or cascade away history that is
 * supposed to survive. Deleting the habit or task itself does clear its
 * history, but by an explicit delete (U8), not by the database's choice.
 *
 * <p>{@code user_id} is the exception, and it IS a real association with
 * {@code ON DELETE CASCADE}: account deletion has to take this table with it.
 * See the delete-ordering note on {@code UserService.deleteUser} — a plain
 * foreign key here would block the delete instead.
 *
 * <p>Schema lives in {@code V13__check_progress_and_entity_check_day.sql}.
 * Hibernate runs {@code ddl-auto: validate} everywhere, and
 * {@code SchemaIndexParityTest} additionally checks that the index and unique
 * constraint named below actually exist in the migrated schema.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Table(
        name = "entity_check_day",
        uniqueConstraints = {
                // R5 — one outcome per entity per day, enforced by the database
                // rather than by whichever writer happens to run first.
                @UniqueConstraint(
                        name = "uk_entity_check_day_owner_day",
                        columnNames = {"owner_type", "owner_id", "day"})
        },
        indexes = {
                // Closing a day (U5) and the export (U8) both scan one user
                // across a date range, every owner type at once.
                @Index(name = "idx_entity_check_day_user_day", columnList = "user_id, day")
        })
public class EntityCheckDay {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 16)
    private CheckDayOwnerType ownerType;

    /** Habit, Task, Routine or User id. Intentionally unconstrained — see the class note. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** The calendar day in the owner's timezone, already resolved by the caller. */
    @Column(name = "day", nullable = false)
    private LocalDate day;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private CheckDayOutcome outcome;

    public EntityCheckDay(User user, CheckDayOwnerType ownerType, UUID ownerId,
                          LocalDate day, CheckDayOutcome outcome) {
        this.user = user;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.day = day;
        this.outcome = outcome;
    }
}
