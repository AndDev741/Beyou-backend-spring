package beyou.beyouapp.backend.domain.mood;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * How one person felt on one day, and what they wrote about it.
 *
 * <p>One row per user per day, enforced by the unique constraint and written through a
 * native {@code ON CONFLICT} upsert: two taps on the dashboard widget half a second apart
 * both pass any check the application could make, so the database is where that has to be
 * settled. See {@code MoodEntryRepository.upsertEntry}.
 *
 * <p>{@code entryDate} is the owner's own day, resolved through {@code UserDateResolver}
 * before the write. It never arrives from the client's clock for today's entry, and a
 * date the client does send (to edit a past day) is interpreted in the owner's zone.
 *
 * <p>Nothing here touches XP. Paying for a feeling makes the scale a chore to farm, and
 * a farmed scale describes nothing. The streak the UI shows is counted from these rows
 * in the client.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(
    name = "mood_entries",
    uniqueConstraints = @UniqueConstraint(
        name = "mood_entries_user_day_key",
        columnNames = {"user_id", "entry_date"}
    )
)
public class MoodEntry {

    /** Lowest point of the scale. Mirrored by the CHECK constraint in V31. */
    public static final int MIN_MOOD = 1;
    /** Highest point of the scale. Mirrored by the CHECK constraint in V31. */
    public static final int MAX_MOOD = 5;
    /**
     * Longest note the API accepts. Not a column limit — {@code note} is {@code text} —
     * so exceeding it is a validation error the client can show rather than a truncation
     * nobody can undo.
     */
    public static final int MAX_NOTE_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    /**
     * The five-point scale, 1 (awful) to 5 (great). An int and not an enum because the
     * week average and the month trend are arithmetic on it; the labels are translated
     * in the clients, and the range is guarded by the DTO and by the CHECK in V31.
     */
    @Column(nullable = false)
    private Integer mood;

    /** The journal. Optional: a mood on its own is a complete entry. */
    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public MoodEntry(User user, LocalDate entryDate, Integer mood, String note, Instant now) {
        this.user = user;
        this.entryDate = entryDate;
        this.mood = mood;
        this.note = note;
        this.createdAt = now;
        this.updatedAt = now;
    }
}
