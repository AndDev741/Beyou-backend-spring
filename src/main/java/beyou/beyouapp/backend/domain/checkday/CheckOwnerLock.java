package beyou.beyouapp.backend.domain.checkday;

import java.util.UUID;

/**
 * The transaction-scoped advisory lock every writer of an owner's {@code CheckProgress}
 * takes before reading the history it recomputes from (KTD26).
 *
 * <p>Three writers recompute the same scalars: {@link CheckDayRecorder} on the request and
 * snapshot paths, and {@link DayCloseService} on the nightly pass. Each does a read of the
 * whole history followed by a write of a derived scalar onto a different table's row, so
 * two of them running concurrently against one owner is a lost update with nothing to
 * detect it — there is no {@code @Version} anywhere in the model, and the
 * {@code ON CONFLICT DO NOTHING} the pass writes with only serialises writers colliding on
 * the <em>same</em> day. The pass writes yesterday while a check writes today; different
 * unique keys, so the conflict clause never fires between them.
 *
 * <p>The sequence lives here rather than the key arithmetic alone. Two writers taking
 * different keys for one owner is a lock that protects nothing, and two writers taking the
 * same pair in opposite orders is a deadlock; owning both halves in one place means a third
 * writer cannot get either wrong. Callers hand in the repository because
 * {@code pg_advisory_xact_lock} is issued as a query and this class holds no injection
 * point of its own.
 */
public final class CheckOwnerLock {

    private CheckOwnerLock() {}

    /**
     * Locks the account first and the owning entity second — the order
     * {@code XpCalculatorService} already writes those two rows in, so two writers taking
     * this pair cannot deadlock against each other. A user-owned row locks once: taking the
     * same key twice would still be safe (advisory locks are re-entrant per session) but it
     * buys nothing.
     *
     * <p>Released by the transaction ending, commit or rollback, so nothing can leak a lock
     * by forgetting to unlock. Callers must already be inside a transaction — outside one,
     * each statement would take and drop the lock on its own and guard nothing.
     */
    public static void takeUserThenOwner(EntityCheckDayRepository repository, UUID userId,
                                         CheckDayOwnerType ownerType, UUID ownerId) {
        repository.lockCheckOwner(lockClass(CheckDayOwnerType.USER), lockObject(userId));
        if (ownerType != CheckDayOwnerType.USER || !userId.equals(ownerId)) {
            repository.lockCheckOwner(lockClass(ownerType), lockObject(ownerId));
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
}
