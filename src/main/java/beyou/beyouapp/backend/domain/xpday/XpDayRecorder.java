package beyou.beyouapp.backend.domain.xpday;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The write side of the XP history: every change to a total also lands in a day.
 *
 * <p>Called from {@code XpCalculatorService}, which is the single place category, habit,
 * routine and user XP is mutated. That is what makes the history trustworthy rather
 * than best-effort — there is no second path that moves XP without passing through
 * here, so the sum of an owner's rows and its running total describe the same events.
 *
 * <p>The day is the account's, not the server's: {@link UserDateResolver} resolves it
 * in the user's timezone, so a habit checked at 23:00 in São Paulo lands on Tuesday
 * rather than on Wednesday's bar.
 *
 * <p>Failures propagate. The first version caught and logged them, on the reasoning
 * that a chart is not worth failing a check-in over, and that reasoning does not
 * survive contact with the transaction: these writes join the caller's, so anything
 * that fails here has already marked it rollback-only, and catching the exception only
 * hides which line caused the rollback the caller is going to get anyway. It is the
 * same trap the deletion attempt counter fell into.
 *
 * <p>So history and totals share a fate on purpose. A check-in that rolls back must not
 * leave a bar behind claiming it happened, and a bar that cannot be written means
 * something is wrong that a silent chart would only postpone. In practice there is no
 * legitimate call that can fail: the account exists (it is authenticated), and the
 * upsert's whole job is to absorb the one conflict that occurs.
 *
 * <p>The swallow also hid a total failure once. The queries are {@code @Modifying},
 * which needs a transaction, the methods carried no {@code @Transactional}, and every
 * write logged a warning nobody read. The integration test is what found it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XpDayRecorder {

    private final EntityXpDayRepository repository;

    /**
     * Records a delta against one entity. Positive for a gain, negative for a return.
     *
     * <p>{@code @Transactional} with the default propagation, so it joins the caller's
     * transaction rather than opening its own. XP awarded and XP recorded live or die
     * together. It also makes the method callable on its own, which a modifying query
     * is not without one.
     *
     * <p>A zero delta writes nothing: there is no day on which a category earned zero,
     * only days it earned nothing, and those are the gaps the read path fills.
     */
    @Transactional
    public void record(User user, XpDayOwnerType ownerType, UUID ownerId, double xp) {
        if (user == null || ownerId == null || xp == 0) {
            return;
        }
        repository.addXp(user.getId(), ownerType.name(), ownerId,
                UserDateResolver.today(user), xp);
    }

    /** The same delta against several owners of one kind, as a check-in does to categories. */
    public void recordAll(User user, XpDayOwnerType ownerType, Collection<UUID> ownerIds, double xp) {
        if (ownerIds == null) {
            return;
        }
        ownerIds.forEach(ownerId -> record(user, ownerType, ownerId, xp));
    }

    /**
     * Forgets one entity's series, for the delete paths.
     *
     * <p>{@code owner_id} carries no foreign key, so nothing in the database will do
     * this. Without the call, deleting a habit leaves its bars behind with no way to
     * reach or attribute them — the same shape of leak schedules had.
     */
    @Transactional
    public void forget(XpDayOwnerType ownerType, UUID ownerId) {
        if (ownerId == null) {
            return;
        }
        int removed = repository.deleteAllByOwner(ownerType, ownerId);
        if (removed > 0) {
            log.info("Dropped {} XP history rows for {} {}", removed, ownerType, ownerId);
        }
    }
}
