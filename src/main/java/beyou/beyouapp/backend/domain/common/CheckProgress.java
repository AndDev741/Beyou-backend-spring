package beyou.beyouapp.backend.domain.common;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * R1 — the check/streak scalars every checkable entity carries: Habit, a
 * recurring Task, a Routine and the User itself. One-time tasks are checkable
 * once and never build a streak (R4), so nothing ever writes to theirs.
 *
 * <p>Deliberately NOT extra fields on {@link XpProgress} (KTD3): Category earns
 * XP but is never checked, and folding these in would give every category row
 * five columns that stay zero for the life of the account.
 *
 * <p>Only the scalars live here. The per-day outcome history is a side table
 * ({@code beyou.beyouapp.backend.domain.checkday.EntityCheckDay}) because an
 * {@code @Embeddable} cannot be lazy and {@code SecurityFilter} loads the whole
 * {@code User} on every authenticated request — an unbounded per-day structure
 * mapped onto the user row would be read in full, forever (KTD2).
 *
 * <p>Every field carries an explicit {@code @Column(name = ...)}, following
 * {@code FeedbackContext} rather than {@link XpProgress}: the bare field names
 * this type would otherwise generate sit next to the pre-existing
 * {@code habits.constance} and {@code users.max_constance} columns, and the
 * {@code check_} prefix keeps the new scalars unmistakably separate from the
 * old constance counters they will eventually replace.
 *
 * <p>The three counters are {@code NOT NULL DEFAULT 0} in
 * {@code V13__check_progress_and_entity_check_day.sql} and initialised here to
 * match. That pairing is load-bearing: Hibernate materialises a NULL embeddable
 * reference when every mapped column of the embeddable is null, so a row
 * written before the migration would come back with {@code getCheckProgress()
 * == null} and NPE on first read — the same trap {@code HabitMapper} already
 * guards against for {@code xpProgress}. The two dates stay nullable; an
 * account that has never checked anything genuinely has no first or last day.
 */
@Embeddable
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CheckProgress {

    /** Days in the streak running up to and including the last check-in. */
    @Column(name = "check_current_streak", nullable = false)
    private int currentStreak = 0;

    /** Longest streak ever reached. Never decreases. */
    @Column(name = "check_best_streak", nullable = false)
    private int bestStreak = 0;

    /** Lifetime count of days closed as DONE. */
    @Column(name = "check_total_check_ins", nullable = false)
    private int totalCheckIns = 0;

    /** Null until the first check-in ever. */
    @Column(name = "check_first_check_in_date")
    private LocalDate firstCheckInDate;

    /** Null until the first check-in ever; the anchor the streak is counted back from. */
    @Column(name = "check_last_check_in_date")
    private LocalDate lastCheckInDate;
}
