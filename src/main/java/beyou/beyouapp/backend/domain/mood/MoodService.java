package beyou.beyouapp.backend.domain.mood;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.mood.dto.MoodEntryResponseDTO;
import beyou.beyouapp.backend.domain.mood.dto.SetMoodLevelDTO;
import beyou.beyouapp.backend.domain.mood.dto.UpsertMoodEntryDTO;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

/**
 * Daily mood and journaling.
 *
 * <p>Three rules live here and nowhere else, because two clients would drift on all three:
 *
 * <ol>
 *   <li><b>What day it is.</b> Always {@code UserDateResolver}, never the client's clock and
 *       never the server's zone. These rows are permanent history and an off-by-one day
 *       cannot be repaired later.
 *   <li><b>No future days.</b> A mood is a report, not a plan. Refusing them here means the
 *       month calendar's disabled cells are a convenience, not the enforcement.
 *   <li><b>One row per day.</b> Writes go through a native {@code ON CONFLICT} upsert, so two
 *       taps on the widget half a second apart settle in the database rather than racing.
 * </ol>
 *
 * <p>Deliberately not cached. Every read is a date range, so a {@code @Cacheable} keyed on the
 * user would serve one range's answer for another, and a composite key would need its own
 * eviction path on every write. The query is a single index scan over at most three months of
 * one-row-per-day, which is not worth a cache's staleness surface.
 *
 * <p>No XP, no {@code RefreshUiDTO}, no cache eviction: nothing else in the product derives
 * from these rows.
 */
@Service
@RequiredArgsConstructor
public class MoodService {

    /**
     * How many days one range read may span. Three months plus a couple of days, so the month
     * calendar can ask for a whole month with padding weeks either side and still be one call.
     */
    static final int MAX_RANGE_DAYS = 92;

    /** Default window when the caller names neither end: the week the widget draws. */
    static final int DEFAULT_RANGE_DAYS = 7;

    private final MoodEntryRepository moodEntryRepository;
    private final MoodMapper moodMapper;
    private final EntityManager entityManager;

    /**
     * Not injected: there is no {@code Clock} bean in this context, and every other class
     * that needs one holds it the same way (see {@code RoutineSnapshotScheduler},
     * {@code EngagementNudgeScheduler}). Non-final so Lombok leaves it out of the generated
     * constructor, package-private setter so a test can move the day without waiting for it.
     */
    private Clock clock = Clock.systemDefaultZone();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * The entries in a window of days, newest first.
     *
     * @param from first day inclusive; defaults to {@code to} minus six days
     * @param to   last day inclusive; defaults to the caller's today, in their own zone
     */
    @Transactional(readOnly = true)
    public List<MoodEntryResponseDTO> getRange(User user, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : UserDateResolver.today(user, clock);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);

        if (start.isAfter(end)) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "'from' is after 'to'");
        }
        // Counting days inclusively, so a single-day window is 1 and the limit reads the way
        // it is written.
        long days = end.toEpochDay() - start.toEpochDay() + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST,
                    "Range of " + days + " days exceeds the maximum of " + MAX_RANGE_DAYS);
        }

        return moodEntryRepository
                .findByUserIdAndEntryDateBetweenOrderByEntryDateDesc(user.getId(), start, end)
                .stream()
                .map(moodMapper::toResponseDTO)
                .toList();
    }

    /** One day, or nothing when it was never recorded. */
    @Transactional(readOnly = true)
    public Optional<MoodEntryResponseDTO> getDay(User user, LocalDate date) {
        return moodEntryRepository.findByUserIdAndEntryDate(user.getId(), date)
                .map(moodMapper::toResponseDTO);
    }

    /**
     * Replaces the day's entry: both the level and the note.
     *
     * <p>A null note clears the note, because this is a PUT and the body describes the day's
     * final state. Clients that only mean to change the level call {@link #setLevel} instead.
     */
    @Transactional
    public MoodEntryResponseDTO upsert(User user, LocalDate date, UpsertMoodEntryDTO dto) {
        return write(user, date, dto.mood(), dto.note(), true);
    }

    /**
     * Sets the day's level and leaves any note exactly as it was.
     *
     * <p>Creates the entry when the day has none, so the widget's first tap is a complete
     * interaction on its own.
     */
    @Transactional
    public MoodEntryResponseDTO setLevel(User user, LocalDate date, SetMoodLevelDTO dto) {
        return write(user, date, dto.mood(), null, false);
    }

    /**
     * The one write path.
     *
     * <p>Delegates to a native {@code ON CONFLICT} upsert rather than reading, mutating and
     * saving. See {@code MoodEntryRepository.upsertEntry} for why: a read-then-save loses a
     * double-tap race, and the loser's exception poisons the transaction it would have to
     * retry in.
     *
     * @param replaceNote when true the note is set to {@code note} (null clears it); when false
     *                    the stored note is left untouched and {@code note} is ignored
     */
    private MoodEntryResponseDTO write(
            User user, LocalDate date, Integer mood, String note, boolean replaceNote) {
        LocalDate today = UserDateResolver.today(user, clock);
        if (date.isAfter(today)) {
            throw new BusinessException(ErrorKey.MOOD_FUTURE_DATE,
                    "Cannot record a mood for a day that has not happened yet");
        }

        Instant now = clock.instant();
        // Only used when the row is new; the ON CONFLICT branch keeps the existing id.
        UUID newId = UUID.randomUUID();
        if (replaceNote) {
            moodEntryRepository.upsertEntry(newId, user.getId(), date, mood, normalise(note), now);
        } else {
            moodEntryRepository.upsertLevel(newId, user.getId(), date, mood, now);
        }

        // The native write bypasses the persistence context, so anything it left in there for
        // this row is now stale. Clearing before the read is what makes the returned DTO the
        // row that is actually stored, id included.
        entityManager.flush();
        entityManager.clear();

        return moodEntryRepository.findByUserIdAndEntryDate(user.getId(), date)
                .map(moodMapper::toResponseDTO)
                .orElseThrow(() -> new BusinessException(ErrorKey.MOOD_SAVE_FAILED,
                        "Mood entry vanished immediately after being written"));
    }

    /**
     * A note of nothing but whitespace is no note. Stored as null so "did they write
     * anything" is one check everywhere instead of a blank-check at each call site.
     */
    private static String normalise(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Removes a day's entry. Absent days are an error, so a stale UI says so instead of lying. */
    @Transactional
    public void delete(User user, LocalDate date) {
        MoodEntry entry = moodEntryRepository.findByUserIdAndEntryDate(user.getId(), date)
                .orElseThrow(() -> new BusinessException(ErrorKey.MOOD_NOT_FOUND,
                        "No mood entry for that day"));
        try {
            moodEntryRepository.delete(entry);
        } catch (Exception e) {
            throw new BusinessException(ErrorKey.MOOD_DELETE_FAILED, "Error trying to delete the mood entry");
        }
    }

    /**
     * Everything this account ever wrote, for the data download.
     *
     * <p>Unbounded on purpose, unlike {@link #getRange}: an export that stops at ninety-two days
     * would quietly not be an export. It rides the {@code export:} rate-limit tier.
     */
    @Transactional(readOnly = true)
    public List<MoodEntry> findAllForExport(UUID userId) {
        return moodEntryRepository.findByUserIdOrderByEntryDateDesc(userId);
    }
}
