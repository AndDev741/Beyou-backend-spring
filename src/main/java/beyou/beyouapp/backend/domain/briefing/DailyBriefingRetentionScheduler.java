package beyou.beyouapp.backend.domain.briefing;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drops briefings nobody will read again.
 *
 * <p>One row per user per day accumulates forever otherwise, and a briefing is only ever
 * asked about on its own day: the dialog requests today, and the facts it renders are
 * recomputed from the live tables anyway. What is stored is a few lines of generated prose
 * about a morning that has passed.
 *
 * <p>A single delete by date, not a per-user walk. There is no user to anchor on, the sweep
 * has no ordering requirement, and {@code idx_daily_briefing_date} exists for exactly this
 * statement.
 *
 * <p>Runs at a UTC hour that avoids the other two schedulers. The snapshot cycle owns
 * midnight and the 02:00-03:00 day-close window in every timezone, and the engagement pass
 * wakes at half past every hour — nothing breaks if they overlap, but there is no reason to
 * make three jobs contend for the same connection pool at the same moment. Unlike those
 * two, this one is not per-timezone: retention is measured in whole days and a day either
 * side of a boundary changes nothing about what is safe to delete.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DailyBriefingRetentionScheduler {

    private final DailyBriefingRepository repository;

    /**
     * How many days of briefings to keep.
     *
     * <p>Thirty rather than one, even though nothing reads a briefing after its own day.
     * Keeping a month means a support question about what somebody was shown last week can
     * actually be answered, and it is the difference between a handful of rows per account
     * and a handful — the table is narrow and the rows are small. Configurable so it can be
     * cut without a deploy if that ever stops being true.
     */
    @Value("${briefing.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "0 15 4 * * *")
    @Transactional
    public void purgeOldBriefings() {
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
        int removed = repository.deleteOlderThan(cutoff);
        if (removed > 0) {
            log.info("Removed {} daily briefings older than {}", removed, cutoff);
        }
    }
}
