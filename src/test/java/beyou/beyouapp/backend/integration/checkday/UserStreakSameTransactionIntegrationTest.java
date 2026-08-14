package beyou.beyouapp.backend.integration.checkday;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.checkday.UserStreakService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.UserService;

/**
 * The one thing about {@code UserService.markDayCompleted} that cannot be reasoned about
 * from the source: it raises the record from a streak computed by a query, mid-transaction.
 *
 * <p>If that query could not see rows written earlier in the same transaction, the record
 * would be raised from a stale streak and there would be no signal — the number would just
 * be quietly too high. Nothing on the check path writes {@code USER}-owned rows today
 * ({@code DayCloseService} is the only writer, and it runs on its own), so this is a
 * guarantee held for whoever adds one, not a bug being fixed. It is asserted rather than
 * assumed because Hibernate's auto-flush is what makes it true, and auto-flush is exactly
 * the kind of thing a later {@code FlushMode} change turns off silently.
 *
 * <p>The scenario is built so the two answers differ by one. The user completed the day
 * before yesterday; a {@code MISSED} row for yesterday is written into the open
 * transaction; then today is marked complete. Seeing the row, the walk stops at yesterday
 * and the streak is 1. Not seeing it, yesterday reads as neutral, the walk continues into
 * the day before, and the streak is 2.
 */
class UserStreakSameTransactionIntegrationTest extends AbstractIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityCheckDayRepository entityCheckDayRepository;
    @Autowired private UserStreakService userStreakService;
    @Autowired private TransactionTemplate transactionTemplate;

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDate DAY_BEFORE = TODAY.minusDays(2);

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Same transaction user");
        user.setEmail("same-tx-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user.setTimezone("UTC");
        user.setCompletedDays(new HashSet<>(Set.of(DAY_BEFORE)));
        user = userRepository.saveAndFlush(user);
    }

    @Test
    void markDayCompletedSeesACheckDayRowWrittenEarlierInTheSameTransaction() {
        UUID userId = user.getId();

        transactionTemplate.executeWithoutResult(status -> {
            User managed = userRepository.findById(userId).orElseThrow();

            // Deliberately save() and not saveAndFlush(): the row must still be pending in
            // the persistence context when the walk's query runs, or this proves nothing.
            entityCheckDayRepository.save(new EntityCheckDay(
                    managed, CheckDayOwnerType.USER, userId, YESTERDAY, CheckDayOutcome.MISSED));

            userService.markDayCompleted(managed, TODAY);

            assertThat(managed.getMaxConstance())
                    .as("A MISSED row written in this same transaction must end the walk at "
                            + "yesterday; 2 would mean the query flushed nothing and read past it")
                    .isEqualTo(1);
        });

        // And it survives the commit, so the assertion above was not an artefact of the
        // in-memory user object.
        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getMaxConstance()).isEqualTo(1);
        assertThat(userStreakService.streakOf(reloaded, TODAY).currentStreak()).isEqualTo(1);
    }

    @Test
    void withoutThatRowTheSameSetupCountsBothDays() {
        // The control. Same completed days, same call, no row — the walk steps over
        // yesterday as unknown and reaches the day before.
        UUID userId = user.getId();

        transactionTemplate.executeWithoutResult(status -> {
            User managed = userRepository.findById(userId).orElseThrow();
            userService.markDayCompleted(managed, TODAY);
        });

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getMaxConstance()).isEqualTo(2);
    }
}
