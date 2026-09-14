package beyou.beyouapp.backend.domain.briefing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The three writes a briefing needs, each in a transaction of its own.
 *
 * <p>Separate from {@link DailyBriefingService} because two of them have to survive things
 * the service cannot promise. {@link #storeNarrative} is called from a pool thread after the
 * request that started it has already returned, so there is no caller transaction to join.
 * And {@link #findOrCreate} has to be able to absorb a unique-constraint violation without
 * poisoning anything around it, which means its own transaction — a constraint violation
 * inside a shared one marks the whole thing rollback-only in Postgres.
 *
 * <p>The same shape, and for the same reason, as
 * {@code EngagementNudgeService.recordSend} and {@code EmailVerificationWrites}.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DailyBriefingWrites {

    private final DailyBriefingRepository repository;

    /**
     * The row for this user and day, created if it is not there yet.
     *
     * <p>REQUIRES_NEW so the insert commits on its own. Two clients opening the dashboard
     * within the same second both miss the read and both insert; the loser takes a
     * {@code DataIntegrityViolationException} off {@code daily_briefing_user_day_key} and
     * re-reads the winner's row rather than creating a second one, which is what keeps the
     * account from paying for two model calls on the same morning.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DailyBriefing findOrCreate(User user, LocalDate date) {
        return repository.findByUserIdAndBriefingDate(user.getId(), date)
                .orElseGet(() -> insert(user, date));
    }

    private DailyBriefing insert(User user, LocalDate date) {
        try {
            return repository.saveAndFlush(new DailyBriefing(user, date));
        } catch (DataIntegrityViolationException raced) {
            log.debug("Briefing row for user {} on {} appeared while creating it", user.getId(), date);
            return repository.findByUserIdAndBriefingDate(user.getId(), date)
                    .orElseThrow(() -> raced);
        }
    }

    /**
     * Stores the generated prose against a row.
     *
     * <p>Reached from a pool thread when the model answered after the request's deadline had
     * already passed, as well as from the request itself when it answered in time. Written
     * by id rather than by entity because the instance the request held belongs to a
     * transaction that has since closed.
     *
     * <p>A row that has vanished is not an error: the retention sweep or an account deletion
     * can remove it while a slow call is still in flight. There is nothing to repair, and
     * nobody is waiting on the answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeNarrative(UUID briefingId, NarrativeStatus status, String narrativeJson) {
        repository.findById(briefingId).ifPresentOrElse(row -> {
            row.setNarrativeStatus(status);
            row.setNarrativeJson(narrativeJson);
            repository.save(row);
        }, () -> log.debug("Briefing {} no longer exists — narrative discarded", briefingId));
    }

    /**
     * Marks this day's dialog as acknowledged.
     *
     * <p>Idempotent and deliberately not re-stamped: the first acknowledgement is the one
     * that matters, and overwriting it on every later call would make the value useless for
     * telling how long somebody took to open the app.
     */
    @Transactional
    public void markSeen(User user, LocalDate date) {
        repository.findByUserIdAndBriefingDate(user.getId(), date).ifPresent(row -> {
            if (row.getSeenAt() == null) {
                row.setSeenAt(Instant.now());
                repository.save(row);
            }
        });
    }
}
