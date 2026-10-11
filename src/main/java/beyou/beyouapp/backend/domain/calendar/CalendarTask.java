package beyou.beyouapp.backend.domain.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import beyou.beyouapp.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * A one-off obligation on a date: "sign the lease", "book the dentist".
 *
 * <p>Its own entity rather than a {@code Task} with a date. A routine task completes only
 * through a routine check, pays streak-aware XP and deletes itself the day after a one-time
 * check; this one completes on its own, pays a flat amount and stays on its day for good,
 * done or not. A past task nobody completed is still on its own day, marked as not done; it
 * never moves to today.
 *
 * <p>The reminder instant is not stored. It is derived on every read by
 * {@link CalendarReminderTime}, so a change of timezone or default reminder time can never
 * leave a stale instant behind. What is stored is whether anyone reacted to the reminder:
 * {@link #acknowledgedAt} (the browser notification was clicked) and
 * {@link #reminderEmailSentAt} (the fallback mail went out).
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@ToString
@Table(
    name = "calendar_tasks",
    indexes = @Index(name = "idx_calendar_tasks_user_date", columnList = "user_id, task_date")
)
public class CalendarTask {

    public static final int MIN_TITLE_LENGTH = 2;
    /** Mirrors the varchar(255) in V40. */
    public static final int MAX_TITLE_LENGTH = 255;
    /** Mirrors the varchar(1000) in V40. */
    public static final int MAX_NOTE_LENGTH = 1000;
    /** A week. Mirrored by the CHECK constraint in V40; the presets stop at a day. */
    public static final int MAX_ADVANCE_MINUTES = 10080;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinColumn(name = "user_id", nullable = false)
    @ToString.Exclude
    private User user;

    @Column(nullable = false, length = MAX_TITLE_LENGTH)
    private String title;

    @Column(length = MAX_NOTE_LENGTH)
    private String note;

    /** The owner's own day. */
    @Column(name = "task_date", nullable = false)
    private LocalDate taskDate;

    /** Null when the task has no time; the reminder then uses the owner's default time. */
    @Column(name = "task_time")
    private LocalTime taskTime;

    @Column(name = "advance_minutes", nullable = false)
    private int advanceMinutes;

    /** When it was completed, or null. Completion is this column, not a separate flag. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** What completing paid. Un-completing takes back exactly this, then zeroes it. */
    @Column(name = "xp_awarded", nullable = false)
    private double xpAwarded;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "reminder_email_sent_at")
    private Instant reminderEmailSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isCompleted() {
        return completedAt != null;
    }
}
