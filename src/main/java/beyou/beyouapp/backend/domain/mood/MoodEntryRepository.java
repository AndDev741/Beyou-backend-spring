package beyou.beyouapp.backend.domain.mood;

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

@Repository
public interface MoodEntryRepository extends JpaRepository<MoodEntry, UUID> {

    /** The one row for a day, or nothing. The unique constraint is what makes this single. */
    Optional<MoodEntry> findByUserIdAndEntryDate(UUID userId, LocalDate entryDate);

    /**
     * A window of days, newest first. Every read in the product is a range — the widget
     * asks for a week, the calendar for a month — so there is no "find all", and the
     * caller is responsible for bounding the window (see {@code MoodService.MAX_RANGE_DAYS}).
     */
    List<MoodEntry> findByUserIdAndEntryDateBetweenOrderByEntryDateDesc(
            UUID userId, LocalDate from, LocalDate to);

    /** Used by the account export, which wants everything and has its own rate-limit tier. */
    List<MoodEntry> findByUserIdOrderByEntryDateDesc(UUID userId);

    /**
     * Writes the day's level and note in one statement.
     *
     * <p>Native {@code ON CONFLICT} rather than read-then-save. Two taps on the widget half a
     * second apart both pass a JPA existence check, and the loser then hits the unique
     * constraint — inside a transaction, which poisons it, so there is nothing useful to retry
     * with. Postgres resolving the conflict itself removes the race instead of recovering from
     * it, and the caller gets the same answer either way.
     *
     * <p>{@code createdAt} is deliberately absent from the update list: the day was first
     * recorded when it was first recorded.
     */
    @Modifying
    @Query(value = """
            INSERT INTO mood_entries (id, user_id, entry_date, mood, note, created_at, updated_at)
            VALUES (:id, :userId, :entryDate, :mood, :note, :now, :now)
            ON CONFLICT (user_id, entry_date) DO UPDATE
                SET mood = EXCLUDED.mood,
                    note = EXCLUDED.note,
                    updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsertEntry(@Param("id") UUID id,
                     @Param("userId") UUID userId,
                     @Param("entryDate") LocalDate entryDate,
                     @Param("mood") Integer mood,
                     @Param("note") String note,
                     @Param("now") Instant now);

    /**
     * Writes the day's level and leaves any stored note alone.
     *
     * <p>The difference from {@link #upsertEntry} is one missing line in the SET list, and it
     * is the whole reason the widget cannot delete a journal entry it never loaded.
     */
    @Modifying
    @Query(value = """
            INSERT INTO mood_entries (id, user_id, entry_date, mood, note, created_at, updated_at)
            VALUES (:id, :userId, :entryDate, :mood, NULL, :now, :now)
            ON CONFLICT (user_id, entry_date) DO UPDATE
                SET mood = EXCLUDED.mood,
                    updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsertLevel(@Param("id") UUID id,
                     @Param("userId") UUID userId,
                     @Param("entryDate") LocalDate entryDate,
                     @Param("mood") Integer mood,
                     @Param("now") Instant now);
}
