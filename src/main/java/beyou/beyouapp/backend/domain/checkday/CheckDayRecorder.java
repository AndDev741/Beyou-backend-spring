package beyou.beyouapp.backend.domain.checkday;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.CheckProgress;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.routine.schedule.ScheduledOnDayResolver.Standing;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Writes one day's outcome for one owner and re-derives that owner's scalars from the
 * rows that result.
 *
 * <p>The seam is split deliberately: this class owns the write and the locking,
 * {@link CheckProgressCalculator} owns the arithmetic. The calculator is a pure function
 * over a list, so every streak rule is testable without a database, and this class stays
 * small enough to read in one sitting.
 *
 * <p>KTD20 — identity and the date arrive as parameters and are never read from the
 * security context. The agent tools reach this path on a boundedElastic thread with no
 * {@code SecurityContext} at all, and the day-close scheduler has no request behind it.
 *
 * <p>KTD26 — the recompute runs under a transaction-scoped Postgres advisory lock, taken
 * on the user first and the entity second. That is the order {@code XpCalculatorService}
 * already writes those rows in; two writers taking the same pair in the same order cannot
 * deadlock against each other. The lock is released by the transaction, never by this code,
 * which is why {@link Propagation#MANDATORY} is not decoration: without an enclosing
 * transaction the lock would be taken and dropped inside the same statement and guard
 * nothing. Same contract as {@code XpCalculatorService}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CheckDayRecorder {

    private final EntityCheckDayRepository entityCheckDayRepository;

    /**
     * Stamps {@code outcome} on {@code day} for one owner and recomputes its scalars.
     *
     * <p>Exactly one row per (owner type, owner id, day) survives: an existing row for the
     * day is overwritten in place rather than duplicated, so checking the same habit twice
     * in one day leaves one row and one increment.
     *
     * @param owner    the account the row belongs to. Also the first advisory lock taken.
     * @param progress the owner entity's own embedded scalars. Mutated in place on success,
     *                 so Hibernate's dirty check picks the update up with no explicit save.
     *                 May be null for callers that only want the row written.
     * @return the recomputed scalars.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckProgress record(User owner, CheckDayOwnerType ownerType, UUID ownerId,
                                CheckProgress progress, LocalDate day, CheckDayOutcome outcome) {
        if (owner == null || owner.getId() == null) {
            throw new IllegalArgumentException("A check day row needs the owning user");
        }
        if (ownerType == null || ownerId == null || day == null || outcome == null) {
            throw new IllegalArgumentException(
                    "A check day row needs an owner type, an owner id, a day and an outcome");
        }

        lockUserThenOwner(owner.getId(), ownerType, ownerId);

        List<EntityCheckDay> history =
                new ArrayList<>(entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(ownerType, ownerId));

        EntityCheckDay row = history.stream()
                .filter(stored -> day.equals(stored.getDay()))
                .findFirst()
                .orElse(null);
        if (row == null) {
            row = new EntityCheckDay(owner, ownerType, ownerId, day, outcome);
            history.add(row);
        } else {
            row.setOutcome(outcome);
        }
        entityCheckDayRepository.save(row);

        int storedBestStreak = progress != null ? progress.getBestStreak() : 0;
        // Anchored on the owner's today, never on the day just written. These scalars mean
        // "as of now": recomputing against a back-dated edit would walk back from that day
        // and report the streak as it stood then, understating a user whose run reaches
        // today, and it would stay understated until the next live check on that owner.
        CheckProgress recomputed = CheckProgressCalculator.recompute(
                history, UserDateResolver.today(owner), storedBestStreak);
        if (progress != null) {
            copyInto(recomputed, progress);
        }

        log.debug("Recorded {} for {} {} on {} — streak {}, record {}, total {}",
                outcome, ownerType, ownerId, day,
                recomputed.getCurrentStreak(), recomputed.getBestStreak(), recomputed.getTotalCheckIns());
        return recomputed;
    }

    /**
     * The outcome a day falls back to once its check is taken away, or {@code null} when
     * the day should carry no row at all.
     *
     * <p>Two of the three absences are facts about the schedule and hold at any hour:
     * belonging to no routine, and belonging to one that does not cover this weekday.
     * Those are stamped immediately.
     *
     * <p>{@code MISSED} is different. It means "scheduled for the day and left unchecked",
     * which only the end of the day can establish. Unchecking at 09:00 says nothing about
     * how the day ends, so an open day is left with no row: R18 reads an absent row as
     * unknown and treats it as neutral, and the insert-only day-close pass (KTD18) stamps
     * {@code MISSED} at close if the user never re-checks. Stamping it early would zero a
     * live streak mid-morning and make the re-check pay its XP against a streak of zero.
     *
     * @param dayClosed whether {@code day} is already over in the owner's timezone.
     */
    public static CheckDayOutcome absenceOutcome(Standing standing, boolean dayClosed) {
        if (standing == null || !standing.inAnyRoutine()) {
            return CheckDayOutcome.NOT_IN_ROUTINE;
        }
        if (!standing.scheduled()) {
            return CheckDayOutcome.NOT_SCHEDULED;
        }
        return dayClosed ? CheckDayOutcome.MISSED : null;
    }

    /**
     * Removes one day's row and re-derives the owner's scalars from what remains.
     *
     * <p>The counterpart to {@link #record}: used when {@link #absenceOutcome} returns
     * {@code null}, meaning the day is still open and has nothing true to say yet.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckProgress clearDay(User owner, CheckDayOwnerType ownerType, UUID ownerId,
                                  CheckProgress progress, LocalDate day) {
        if (owner == null || owner.getId() == null) {
            throw new IllegalArgumentException("Clearing a check day needs the owning user");
        }
        if (ownerType == null || ownerId == null || day == null) {
            throw new IllegalArgumentException(
                    "Clearing a check day needs an owner type, an owner id and a day");
        }

        lockUserThenOwner(owner.getId(), ownerType, ownerId);
        entityCheckDayRepository.deleteOwnerDay(ownerType, ownerId, day);

        List<EntityCheckDay> history = entityCheckDayRepository
                .findByOwnerTypeAndOwnerIdOrderByDayAsc(ownerType, ownerId).stream()
                .filter(stored -> !day.equals(stored.getDay()))
                .toList();

        int storedBestStreak = progress != null ? progress.getBestStreak() : 0;
        // Anchored on the owner's today, never on the day just written. These scalars mean
        // "as of now": recomputing against a back-dated edit would walk back from that day
        // and report the streak as it stood then, understating a user whose run reaches
        // today, and it would stay understated until the next live check on that owner.
        CheckProgress recomputed = CheckProgressCalculator.recompute(
                history, UserDateResolver.today(owner), storedBestStreak);
        if (progress != null) {
            copyInto(recomputed, progress);
        }

        log.debug("Cleared {} {} on {} — streak {}, record {}, total {}",
                ownerType, ownerId, day,
                recomputed.getCurrentStreak(), recomputed.getBestStreak(), recomputed.getTotalCheckIns());
        return recomputed;
    }

    /**
     * User first, then the entity — the order {@code XpCalculatorService} already writes
     * those two rows in. A user-owned row locks once; taking the same key twice would still
     * be safe (advisory locks are re-entrant per session) but it buys nothing.
     */
    private void lockUserThenOwner(UUID userId, CheckDayOwnerType ownerType, UUID ownerId) {
        entityCheckDayRepository.lockCheckOwner(lockClass(CheckDayOwnerType.USER), lockObject(userId));
        if (ownerType != CheckDayOwnerType.USER || !userId.equals(ownerId)) {
            entityCheckDayRepository.lockCheckOwner(lockClass(ownerType), lockObject(ownerId));
        }
    }

    /**
     * The lock's first key. {@code String.hashCode} rather than {@code ordinal()} on
     * purpose: the value has to stay the same across two application versions running side
     * by side during a rolling deploy, and reordering an enum constant is a much easier
     * mistake to make than renaming one.
     */
    private static int lockClass(CheckDayOwnerType ownerType) {
        return ownerType.name().hashCode();
    }

    /**
     * The lock's second key, folded from 128 bits to 32. Collisions are possible and
     * harmless: two unrelated owners sharing a key serialise against each other for the
     * length of one recompute, which is correctness-neutral.
     */
    private static int lockObject(UUID id) {
        long folded = id.getMostSignificantBits() ^ id.getLeastSignificantBits();
        return (int) (folded >> 32) ^ (int) folded;
    }

    private static void copyInto(CheckProgress source, CheckProgress target) {
        target.setCurrentStreak(source.getCurrentStreak());
        target.setBestStreak(source.getBestStreak());
        target.setTotalCheckIns(source.getTotalCheckIns());
        target.setFirstCheckInDate(source.getFirstCheckInDate());
        target.setLastCheckInDate(source.getLastCheckInDate());
    }
}
