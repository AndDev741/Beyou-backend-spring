package beyou.beyouapp.backend.integration.mood;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.common.XpProgress;
import beyou.beyouapp.backend.domain.mood.MoodEntryRepository;
import beyou.beyouapp.backend.domain.mood.MoodService;
import beyou.beyouapp.backend.domain.mood.dto.MoodEntryResponseDTO;
import beyou.beyouapp.backend.domain.mood.dto.SetMoodLevelDTO;
import beyou.beyouapp.backend.domain.mood.dto.UpsertMoodEntryDTO;
import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

/**
 * Mood and journaling against a real Postgres.
 *
 * <p>Real rather than mocked because three of the rules under test are database rules: the unique
 * constraint that makes a day one row, the {@code ON CONFLICT} clause that decides whether a write
 * touches the note, and the CHECK that mirrors the 1..5 scale. A mocked repository cannot fail any
 * of them.
 *
 * <p>The timezone tests use Kiritimati (UTC+14) and Niue (UTC-11). A one-hour offset can be
 * satisfied by a server sitting an hour away; twenty-five hours apart cannot.
 */
@Transactional
class MoodServiceIT extends AbstractIntegrationTest {

    @Autowired private MoodService moodService;
    @Autowired private MoodEntryRepository moodEntryRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private User user;
    private User stranger;

    @BeforeEach
    void seed() {
        user = newUser("mood-owner", "UTC");
        stranger = newUser("mood-stranger", "UTC");
        setClock(Clock.systemDefaultZone());
    }

    @Test
    void setLevel_createsTheDayWhenItHasNoEntry() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        MoodEntryResponseDTO saved = moodService.setLevel(user, today, new SetMoodLevelDTO(4));

        assertThat(saved.date()).isEqualTo(today);
        assertThat(saved.mood()).isEqualTo(4);
        assertThat(saved.note()).isNull();
        assertThat(saved.id()).isNotNull();
    }

    @Test
    void writingTheSameDayTwiceLeavesOneRow() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        MoodEntryResponseDTO first = moodService.setLevel(user, today, new SetMoodLevelDTO(2));
        MoodEntryResponseDTO second = moodService.setLevel(user, today, new SetMoodLevelDTO(5));

        assertThat(second.mood()).isEqualTo(5);
        // Same row, updated. A new id here would mean the unique constraint had been dodged
        // and the day now had two entries.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(moodEntryRepository.findByUserIdOrderByEntryDateDesc(user.getId())).hasSize(1);
    }

    /**
     * The rule the whole two-verb split exists for: someone journals in the morning, taps a face
     * on the dashboard at night, and their writing is still there. PATCH has no field to carry a
     * note in, and the SQL it runs does not name the note column.
     */
    @Test
    void setLevel_leavesAnExistingNoteAlone() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        moodService.upsert(user, today, new UpsertMoodEntryDTO(3, "Long day, but I finished the report."));

        MoodEntryResponseDTO afterTap = moodService.setLevel(user, today, new SetMoodLevelDTO(5));

        assertThat(afterTap.mood()).isEqualTo(5);
        assertThat(afterTap.note()).isEqualTo("Long day, but I finished the report.");
    }

    /** The other half of the same rule: a PUT really does replace, because that is the Save button. */
    @Test
    void upsert_withNoNoteClearsTheNote() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        moodService.upsert(user, today, new UpsertMoodEntryDTO(3, "Something I would rather delete."));

        MoodEntryResponseDTO cleared = moodService.upsert(user, today, new UpsertMoodEntryDTO(3, null));

        assertThat(cleared.note()).isNull();
    }

    @Test
    void upsert_treatsWhitespaceAsNoNote() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        MoodEntryResponseDTO saved = moodService.upsert(user, today, new UpsertMoodEntryDTO(3, "   \n  "));

        assertThat(saved.note()).isNull();
    }

    @Test
    void upsert_stripsSurroundingWhitespaceFromTheNote() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        MoodEntryResponseDTO saved = moodService.upsert(user, today, new UpsertMoodEntryDTO(3, "  kept  "));

        assertThat(saved.note()).isEqualTo("kept");
    }

    @Test
    void pastDaysAreAllowed() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        MoodEntryResponseDTO saved = moodService.setLevel(user, today.minusDays(10), new SetMoodLevelDTO(1));

        assertThat(saved.date()).isEqualTo(today.minusDays(10));
    }

    @Test
    void futureDaysAreRefused() {
        LocalDate tomorrow = LocalDate.now(ZoneId.of("UTC")).plusDays(1);

        assertThatThrownBy(() -> moodService.setLevel(user, tomorrow, new SetMoodLevelDTO(3)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.MOOD_FUTURE_DATE);
    }

    /**
     * "Tomorrow" is not a server fact. At 10:00 UTC it is already the next day in Kiritimati, so
     * an account there must be allowed to record it; the same instant is still yesterday in Niue,
     * where that same date has to be refused.
     */
    @Test
    void futureIsJudgedInTheOwnersZoneNotTheServers() {
        Instant instant = Instant.parse("2026-09-06T10:00:00Z");
        setClock(Clock.fixed(instant, ZoneId.of("UTC")));

        User ahead = newUser("mood-kiritimati", "Pacific/Kiritimati");   // UTC+14 -> 2026-09-07
        User behind = newUser("mood-niue", "Pacific/Niue");              // UTC-11 -> 2026-09-05
        LocalDate theSeventh = LocalDate.of(2026, 9, 7);

        MoodEntryResponseDTO saved = moodService.setLevel(ahead, theSeventh, new SetMoodLevelDTO(4));
        assertThat(saved.date()).isEqualTo(theSeventh);

        assertThatThrownBy(() -> moodService.setLevel(behind, theSeventh, new SetMoodLevelDTO(4)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.MOOD_FUTURE_DATE);
    }

    /** The default window is the week the widget draws, ending on the owner's today. */
    @Test
    void getRange_defaultsToTheLastSevenDaysInTheOwnersZone() {
        setClock(Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), ZoneId.of("UTC")));
        LocalDate today = LocalDate.of(2026, 9, 6);

        moodService.setLevel(user, today, new SetMoodLevelDTO(5));
        moodService.setLevel(user, today.minusDays(6), new SetMoodLevelDTO(1));
        moodService.setLevel(user, today.minusDays(7), new SetMoodLevelDTO(3));

        List<MoodEntryResponseDTO> week = moodService.getRange(user, null, null);

        assertThat(week).extracting(MoodEntryResponseDTO::date)
                .containsExactly(today, today.minusDays(6));
    }

    @Test
    void getRange_isNewestFirst() {
        setClock(Clock.fixed(Instant.parse("2026-09-06T10:00:00Z"), ZoneId.of("UTC")));
        LocalDate today = LocalDate.of(2026, 9, 6);
        moodService.setLevel(user, today.minusDays(2), new SetMoodLevelDTO(1));
        moodService.setLevel(user, today, new SetMoodLevelDTO(2));
        moodService.setLevel(user, today.minusDays(1), new SetMoodLevelDTO(3));

        List<MoodEntryResponseDTO> range = moodService.getRange(user, today.minusDays(2), today);

        assertThat(range).extracting(MoodEntryResponseDTO::date)
                .containsExactly(today, today.minusDays(1), today.minusDays(2));
    }

    @Test
    void getRange_refusesAWindowLongerThanTheLimit() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        assertThatThrownBy(() -> moodService.getRange(user, today.minusDays(365), today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.INVALID_REQUEST);
    }

    @Test
    void getRange_refusesABackwardsWindow() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        assertThatThrownBy(() -> moodService.getRange(user, today, today.minusDays(3)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.INVALID_REQUEST);
    }

    /** Ninety-two days is the documented maximum, so it has to be accepted, not refused. */
    @Test
    void getRange_acceptsExactlyTheLimit() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));

        assertThat(moodService.getRange(user, today.minusDays(91), today)).isEmpty();
    }

    @Test
    void oneAccountNeverSeesAnothersEntries() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        moodService.upsert(user, today, new UpsertMoodEntryDTO(5, "mine"));

        assertThat(moodService.getRange(stranger, today.minusDays(6), today)).isEmpty();
        assertThat(moodService.getDay(stranger, today)).isEmpty();
    }

    @Test
    void deletingSomeoneElsesDayIsNotFound() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        moodService.upsert(user, today, new UpsertMoodEntryDTO(5, "mine"));

        assertThatThrownBy(() -> moodService.delete(stranger, today))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.MOOD_NOT_FOUND);

        // And the owner's row survived the attempt.
        assertThat(moodService.getDay(user, today)).isPresent();
    }

    @Test
    void deleteRemovesTheDay() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        moodService.upsert(user, today, new UpsertMoodEntryDTO(2, "gone soon"));

        moodService.delete(user, today);

        assertThat(moodService.getDay(user, today)).isEmpty();
    }

    @Test
    void deletingADayThatWasNeverRecordedIsNotFound() {
        assertThatThrownBy(() -> moodService.delete(user, LocalDate.now(ZoneId.of("UTC"))))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorKey())
                .isEqualTo(ErrorKey.MOOD_NOT_FOUND);
    }

    @Test
    void exportReturnsEveryDayThisAccountEverWrote() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        moodService.upsert(user, today, new UpsertMoodEntryDTO(4, "today"));
        moodService.upsert(user, today.minusDays(200), new UpsertMoodEntryDTO(2, "long ago"));
        moodService.upsert(stranger, today, new UpsertMoodEntryDTO(1, "not mine"));

        assertThat(moodService.findAllForExport(user.getId()))
                .hasSize(2)
                .extracting(e -> e.getNote())
                .containsExactly("today", "long ago");
    }

    /**
     * A note far longer than the DTO's 4000-character limit still stores, because the column is
     * {@code text}. The limit is a validation error the client can show, never a truncation.
     */
    @Test
    void theNoteColumnHasNoLengthLimitOfItsOwn() {
        LocalDate today = LocalDate.now(ZoneId.of("UTC"));
        String long_ = "a".repeat(10_000);

        MoodEntryResponseDTO saved = moodService.upsert(user, today, new UpsertMoodEntryDTO(3, long_));

        entityManager.flush();
        entityManager.clear();
        assertThat(saved.note()).hasSize(10_000);
    }

    private void setClock(Clock clock) {
        ReflectionTestUtils.invokeMethod(moodService, "setClock", clock);
    }

    private User newUser(String tag, String timezone) {
        User u = new User();
        u.setName(tag);
        u.setEmail(tag + "-" + UUID.randomUUID() + "@test.com");
        u.setPassword("password123");
        u.setGoogleAccount(false);
        u.setTimezone(timezone);
        u.setCompletedDays(new HashSet<>());
        u.setXpDecayStrategy(XpDecayStrategy.GRADUAL);
        u.setXpProgress(new XpProgress(0D, 0, 0D, 50D));
        return userRepository.saveAndFlush(u);
    }
}
