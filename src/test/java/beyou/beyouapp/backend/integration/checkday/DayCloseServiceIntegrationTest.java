package beyou.beyouapp.backend.integration.checkday;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.DayCloseService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The day-close insert, executed for real.
 *
 * <p>The pass writes through a native statement so it can use
 * {@code ON CONFLICT DO NOTHING} — Hibernate has no portable way to express that — and
 * the statement binds six parameters as strings through explicit casts. The unit test
 * mocks the {@code EntityManager}, so it proves the pass decides correctly and proves
 * nothing at all about whether the SQL parses, whether the casts hold, or whether the
 * conflict target names a constraint that exists.
 *
 * <p>The production caller is gated on a wall-clock hour, so no ordinary test run ever
 * reaches the statement. These call {@code closeDay} directly.
 */
class DayCloseServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private DayCloseService dayCloseService;
    @Autowired private EntityCheckDayRepository entityCheckDayRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private User account;
    private LocalDate yesterday;

    @BeforeEach
    void setUp() {
        entityCheckDayRepository.deleteAll();

        account = new User();
        account.setName("Day close");
        account.setEmail("day-close-" + UUID.randomUUID() + "@test.com");
        account.setPassword("irrelevant");
        account.setTimezone("UTC");
        account = userRepository.save(account);
        // prePersist stamps today, but this closes yesterday — an account that did not exist
        // then is correctly floored out, so back-date it to before the window under test.
        account.setCreatedAt(java.sql.Date.valueOf(LocalDate.now().minusDays(10)));
        account = userRepository.save(account);

        yesterday = LocalDate.now().minusDays(1);
    }

    private Habit habitCreatedOn(LocalDate created) {
        Habit habit = new Habit();
        habit.setName("Read");
        habit.setIconId("book");
        habit.setImportance(3);
        habit.setDificulty(3);
        habit.setUser(account);
        habit.setCategories(List.of());
        habit.getXpProgress().setNextLevelXp(ExperienceLevel.BEGINNER.getXp() + 50D);
        habit = habitRepository.save(habit);
        // prePersist stamps today; the floor is what this test is about, so set it after.
        habit.setCreatedAt(java.sql.Date.valueOf(created));
        return habitRepository.save(habit);
    }

    private List<EntityCheckDay> rowsFor(CheckDayOwnerType type, UUID ownerId) {
        return entityCheckDayRepository.findByOwnerTypeAndOwnerIdOrderByDayAsc(type, ownerId);
    }

    @Test
    void theNativeInsertParsesBindsAndLands() {
        Habit habit = habitCreatedOn(yesterday.minusDays(3));

        int written = transactionTemplate.execute(status -> dayCloseService.closeDay(account, yesterday));

        assertThat(written)
                .as("a row for the habit, and one for the account itself")
                .isGreaterThanOrEqualTo(2);

        List<EntityCheckDay> habitRows = rowsFor(CheckDayOwnerType.HABIT, habit.getId());
        assertThat(habitRows).hasSize(1);
        assertThat(habitRows.get(0).getDay())
                .as("the day cast landed as a real date, not shifted or truncated")
                .isEqualTo(yesterday);
        assertThat(habitRows.get(0).getUser().getId())
                .as("the user id cast landed as a uuid")
                .isEqualTo(account.getId());
        assertThat(habitRows.get(0).getOutcome())
                .as("no routine holds this habit, so it belongs to none")
                .isEqualTo(CheckDayOutcome.NOT_IN_ROUTINE);

        assertThat(rowsFor(CheckDayOwnerType.USER, account.getId()))
                .as("the account gets its own row, which the user streak reads")
                .hasSize(1);
    }

    @Test
    void theConflictTargetResolvesSoASecondRunWritesNothing() {
        habitCreatedOn(yesterday.minusDays(3));

        int first = transactionTemplate.execute(status -> dayCloseService.closeDay(account, yesterday));
        int second = transactionTemplate.execute(status -> dayCloseService.closeDay(account, yesterday));

        assertThat(first).isGreaterThan(0);
        assertThat(second)
                .as("ON CONFLICT DO NOTHING against uk_entity_check_day_owner_day, not an error")
                .isZero();
        assertThat(entityCheckDayRepository.findByUserIdAndDay(account.getId(), yesterday))
                .as("still exactly one row per owner after two passes")
                .hasSize(2);
    }

    @Test
    void anExistingPresenceRowSurvivesTheClose() {
        Habit habit = habitCreatedOn(yesterday.minusDays(3));
        transactionTemplate.execute(status -> {
            entityCheckDayRepository.save(new EntityCheckDay(
                    account, CheckDayOwnerType.HABIT, habit.getId(), yesterday, CheckDayOutcome.DONE));
            return null;
        });

        transactionTemplate.execute(status -> dayCloseService.closeDay(account, yesterday));

        assertThat(rowsFor(CheckDayOwnerType.HABIT, habit.getId()))
                .singleElement()
                .extracting(EntityCheckDay::getOutcome)
                .as("insert-only: the pass never overwrites what the request path already wrote")
                .isEqualTo(CheckDayOutcome.DONE);
    }

    @Test
    void aHabitCreatedAfterTheClosingDayGetsNoRow() {
        Habit habit = habitCreatedOn(yesterday.plusDays(1));

        transactionTemplate.execute(status -> dayCloseService.closeDay(account, yesterday));

        assertThat(rowsFor(CheckDayOwnerType.HABIT, habit.getId()))
                .as("a habit cannot have missed a day it did not exist for")
                .isEmpty();
    }
}
