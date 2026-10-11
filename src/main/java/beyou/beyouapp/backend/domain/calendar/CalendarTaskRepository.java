package beyou.beyouapp.backend.domain.calendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface CalendarTaskRepository extends JpaRepository<CalendarTask, UUID> {

    /**
     * The task, if it is this user's. Ownership is part of the lookup rather than a check after
     * it, so a task that exists but belongs to someone else reads exactly like one that does not
     * exist: there is no answer here that tells a caller which ids are taken.
     */
    Optional<CalendarTask> findByIdAndUserId(UUID id, UUID userId);

    /** A window of days. The caller bounds it (see {@code CalendarService.MAX_RANGE_DAYS}). */
    List<CalendarTask> findByUserIdAndTaskDateBetween(UUID userId, LocalDate from, LocalDate to);

    /** The reminders still waiting for a reaction, in a window of days, for one user. */
    List<CalendarTask> findByUserIdAndCompletedAtIsNullAndAcknowledgedAtIsNullAndTaskDateBetween(
            UUID userId, LocalDate from, LocalDate to);

    /**
     * Every account's tasks that might owe a reminder mail, owners loaded in the same query.
     *
     * <p>The window is in days because the instant itself is computed in Java, per owner, from
     * a timezone and a default time that live elsewhere. The partial index from V40 covers
     * exactly these three IS NULLs.
     */
    @Query("""
            SELECT t FROM CalendarTask t JOIN FETCH t.user
            WHERE t.completedAt IS NULL
              AND t.acknowledgedAt IS NULL
              AND t.reminderEmailSentAt IS NULL
              AND t.taskDate BETWEEN :from AND :to
            """)
    List<CalendarTask> findReminderEmailCandidates(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** How many reminder mails went out since an instant. Backed by a partial index from V40. */
    long countByReminderEmailSentAtGreaterThanEqual(Instant since);

    /**
     * Records that the reminder mail went out, if the task is still the one that was mailed.
     *
     * <p>Conditional on purpose: the mail is sent before this runs, and in between the owner may
     * have moved the task. A move clears this column so the new reminder fires, and an
     * unconditional write here would stamp "sent" on that new reminder and swallow it. Matching
     * on the {@code updatedAt} that was read before the send turns the late write into a no-op
     * when anything was edited in between. The cost is a second mail in the rare case where only
     * the title changed during the send, which beats a reminder that never comes.
     *
     * @return 1 when the row was marked, 0 when the task changed under the send
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE CalendarTask t SET t.reminderEmailSentAt = :sentAt
            WHERE t.id = :id
              AND t.reminderEmailSentAt IS NULL
              AND t.updatedAt = :seenUpdatedAt
            """)
    int markReminderEmailSent(@Param("id") UUID id, @Param("seenUpdatedAt") Instant seenUpdatedAt,
                              @Param("sentAt") Instant sentAt);

    /** Everything, for the account export, oldest day first. */
    List<CalendarTask> findByUserIdOrderByTaskDateAscCreatedAtAsc(UUID userId);
}
