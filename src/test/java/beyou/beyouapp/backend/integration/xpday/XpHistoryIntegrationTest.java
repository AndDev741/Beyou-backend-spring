package beyou.beyouapp.backend.integration.xpday;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.xpday.EntityXpDayRepository;
import beyou.beyouapp.backend.domain.xpday.XpDayOwnerType;
import beyou.beyouapp.backend.domain.xpday.XpDayRecorder;
import beyou.beyouapp.backend.domain.xpday.XpHistoryService;
import beyou.beyouapp.backend.domain.xpday.dto.XpHistoryResponseDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XP over time, against a real database.
 *
 * <p>Two of the three things this feature rests on cannot be checked anywhere else. The
 * write is an upsert whose whole purpose is to survive two check-ins landing together,
 * and a mock repository has no unique constraint to conflict on. The read fills the
 * gaps between the days that exist, and gaps are the normal case — nobody earns XP in
 * every category every day.
 *
 * <p>The third is the delete. {@code owner_id} carries no foreign key, deliberately, so
 * nothing in the schema will clean up after a deleted category. That is exactly the
 * shape of leak schedules had, found by hand in a dev database, so it is pinned here
 * rather than trusted.
 */
class XpHistoryIntegrationTest extends AbstractIntegrationTest {

    private static final String EMAIL = "xp-history@beyou.test";

    @Autowired UserRepository userRepository;
    @Autowired UserService userService;
    @Autowired CategoryService categoryService;
    @Autowired XpDayRecorder recorder;
    @Autowired XpHistoryService historyService;
    @Autowired EntityXpDayRepository repository;

    private User user;

    @BeforeEach
    void setUp() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> userService.deleteUser(existing));

        User fresh = new User();
        fresh.setName("someone earning XP");
        fresh.setEmail(EMAIL);
        fresh.setPassword("placeholder");
        fresh.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        user = userRepository.saveAndFlush(fresh);
    }

    /**
     * The reason the write is a native upsert instead of read-modify-write: two
     * check-ins on the same day would both read the same bucket, both add their own XP
     * to it, and the second save would erase the first.
     */
    @Test
    @DisplayName("several gains on one day add up in one bucket")
    void repeatedGainsAccumulateInsteadOfOverwriting() {
        UUID categoryId = seedCategory("Health");

        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 10);
        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 5);
        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 2.5);

        assertThat(todayValueFor(categoryId)).isEqualTo(17.5);
    }

    /** Unchecking gives the XP back, so the bar shrinks rather than keeping its peak. */
    @Test
    @DisplayName("a negative delta takes the day back down")
    void undoingACheckInGivesTheDayBack() {
        UUID categoryId = seedCategory("Health");

        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 12);
        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, -12);

        assertThat(todayValueFor(categoryId)).isZero();
    }

    /**
     * The table holds only the days something happened. A chart drawn straight from
     * those rows would put Monday next to Thursday and call it a week.
     */
    @Test
    @DisplayName("the series has one entry per day of the window, gaps included")
    void theSeriesIsDense() {
        UUID categoryId = seedCategory("Health");
        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 30);

        XpHistoryResponseDTO history = historyService.history(user, 7);

        assertThat(history.days()).hasSize(7);
        assertThat(history.to()).isEqualTo(LocalDate.now(java.time.ZoneId.of(user.getTimezone())));

        List<Double> values = seriesFor(history, categoryId);
        assertThat(values).hasSize(7);
        // Today is the last bar, which is the one the mockup highlights.
        assertThat(values.get(6)).isEqualTo(30);
        assertThat(values.subList(0, 6)).containsOnly(0d);
    }

    /** A window is a window: nothing outside it comes back, however much happened. */
    @Test
    @DisplayName("days outside the window are left out")
    void theWindowIsRespected() {
        UUID categoryId = seedCategory("Health");
        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 30);

        XpHistoryResponseDTO history = historyService.history(user, 1);

        assertThat(history.days()).hasSize(1);
        assertThat(history.from()).isEqualTo(history.to());
        assertThat(seriesFor(history, categoryId)).containsExactly(30d);
    }

    /** Four kinds of entity carry XP, and the point of the table is that all four fit. */
    @Test
    @DisplayName("one window answers for every kind of owner at once")
    void everyOwnerTypeSharesTheWindow() {
        UUID categoryId = seedCategory("Health");
        UUID habitId = UUID.randomUUID();
        UUID routineId = UUID.randomUUID();

        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 10);
        recorder.record(user, XpDayOwnerType.HABIT, habitId, 4);
        recorder.record(user, XpDayOwnerType.ROUTINE, routineId, 6);
        recorder.record(user, XpDayOwnerType.USER, user.getId(), 10);

        XpHistoryResponseDTO history = historyService.history(user, 7);

        assertThat(history.series()).hasSize(4);
        assertThat(history.series().stream().map(XpHistoryResponseDTO.OwnerSeries::ownerType))
                .containsExactlyInAnyOrder(XpDayOwnerType.CATEGORY, XpDayOwnerType.HABIT,
                        XpDayOwnerType.ROUTINE, XpDayOwnerType.USER);
    }

    /**
     * owner_id has no foreign key on purpose, so the database will not do this. Without
     * the explicit delete the rows survive with nothing able to reach or attribute them.
     */
    @Test
    @DisplayName("deleting a category takes its series with it")
    void deletingTheOwnerForgetsItsHistory() {
        UUID categoryId = seedCategory("Health");
        recorder.record(user, XpDayOwnerType.CATEGORY, categoryId, 20);
        assertThat(historyService.history(user, 7).series()).hasSize(1);

        categoryService.deleteCategory(categoryId.toString(), user.getId());

        assertThat(historyService.history(user, 7).series()).isEmpty();
    }

    /** Someone else's XP is not yours, however many rows share the day. */
    @Test
    @DisplayName("the window only ever answers for its own account")
    void historyIsScopedToTheAccount() {
        UUID mine = seedCategory("Health");
        recorder.record(user, XpDayOwnerType.CATEGORY, mine, 10);

        User other = new User();
        other.setName("somebody else");
        other.setEmail("xp-history-other@beyou.test");
        other.setPassword("placeholder");
        other.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        userRepository.findByEmail(other.getEmail()).ifPresent(userService::deleteUser);
        User saved = userRepository.saveAndFlush(other);
        recorder.record(saved, XpDayOwnerType.CATEGORY, UUID.randomUUID(), 99);

        XpHistoryResponseDTO history = historyService.history(user, 7);

        assertThat(history.series()).hasSize(1);
        assertThat(history.series().get(0).ownerId()).isEqualTo(mine);

        userService.deleteUser(saved);
    }

    /**
     * History and totals share a fate, and that is a decision rather than an accident.
     *
     * The first version caught and logged instead, on the reasoning that a chart is not
     * worth failing a check-in over. That reasoning does not survive the transaction:
     * the write joins the caller's, so anything failing here has already marked it
     * rollback-only and the catch only hides which line caused the rollback the caller
     * gets anyway. Same trap the deletion attempt counter fell into.
     */
    @Test
    @DisplayName("a history write that cannot happen surfaces rather than hiding")
    void recordingDoesNotSwallowTheImpossible() {
        // No such user row, so the insert violates its foreign key. No authenticated
        // call can reach this state; the test exists to pin that it is not silent.
        User ghost = new User();
        ghost.setId(UUID.randomUUID());
        ghost.setTimezone("UTC");

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> recorder.record(ghost, XpDayOwnerType.CATEGORY, UUID.randomUUID(), 5))
                .isInstanceOf(RuntimeException.class);
    }

    private UUID seedCategory(String name) {
        categoryService.createCategory(new CategoryRequestDTO(
                name, "lucide:heart", "seeded", ExperienceLevel.BEGINNER), user.getId());
        return categoryService.getAllCategories(user.getId()).stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private double todayValueFor(UUID ownerId) {
        return seriesFor(historyService.history(user, 1), ownerId).get(0);
    }

    private List<Double> seriesFor(XpHistoryResponseDTO history, UUID ownerId) {
        return history.series().stream()
                .filter(s -> s.ownerId().equals(ownerId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no series for " + ownerId))
                .values();
    }
}
