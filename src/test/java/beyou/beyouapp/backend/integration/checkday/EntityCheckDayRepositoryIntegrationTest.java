package beyou.beyouapp.backend.integration.checkday;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.checkday.CheckDayOutcome;
import beyou.beyouapp.backend.domain.checkday.CheckDayOwnerType;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V13 schema, exercised against a real PostgreSQL.
 *
 * <p>Nothing here has a service behind it yet — this locks in the guarantees the
 * later units build on: one row per entity per day, history that outlives the
 * entity it describes, and an account delete that is never blocked by it.
 */
class EntityCheckDayRepositoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EntityCheckDayRepository entityCheckDayRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private HabitRepository habitRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private EntityManager entityManager;

    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    private User user;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Check Day IT User");
        user.setEmail("check-day-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        ownerId = UUID.randomUUID();
    }

    private EntityCheckDay row(CheckDayOwnerType type, UUID owner, LocalDate day, CheckDayOutcome outcome) {
        return entityCheckDayRepository.saveAndFlush(new EntityCheckDay(user, type, owner, day, outcome));
    }

    // --- R5: one row per entity per day -------------------------------------

    @Test
    void aSecondRowForTheSameOwnerAndDayIsRejectedByTheDatabase() {
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);

        EntityCheckDay duplicate =
                new EntityCheckDay(user, CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.SKIPPED);

        assertThatThrownBy(() -> entityCheckDayRepository.saveAndFlush(duplicate))
                .as("uk_entity_check_day_owner_day must reject a second outcome for the same day")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameIdUnderTwoOwnerTypesIsNotADuplicate() {
        // Ids are unique per table, not across tables. The key includes the type
        // precisely so a habit and a routine that happen to collide never do.
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);

        assertThatCode(() -> row(CheckDayOwnerType.ROUTINE, ownerId, DAY, CheckDayOutcome.MISSED))
                .doesNotThrowAnyException();
    }

    @Test
    void theSameOwnerOnTwoDaysIsNotADuplicate() {
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);

        assertThatCode(() -> row(CheckDayOwnerType.HABIT, ownerId, DAY.plusDays(1), CheckDayOutcome.MISSED))
                .doesNotThrowAnyException();
    }

    // --- R6: the outcome vocabulary -----------------------------------------

    @Test
    void everyOutcomeInTheEnumIsAcceptedByTheCheckConstraint() {
        // The CHECK constraint in V13 is a hand-written copy of the enum. If the
        // two ever drift, one of these inserts fails.
        for (CheckDayOutcome outcome : CheckDayOutcome.values()) {
            UUID owner = UUID.randomUUID();
            assertThatCode(() -> row(CheckDayOwnerType.HABIT, owner, DAY, outcome))
                    .as("outcome %s must satisfy entity_check_day_outcome_check", outcome)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void everyOwnerTypeInTheEnumIsAcceptedByTheCheckConstraint() {
        for (CheckDayOwnerType type : CheckDayOwnerType.values()) {
            UUID owner = UUID.randomUUID();
            assertThatCode(() -> row(type, owner, DAY, CheckDayOutcome.DONE))
                    .as("owner type %s must satisfy entity_check_day_owner_type_check", type)
                    .doesNotThrowAnyException();
        }
    }

    // --- reads later units depend on ----------------------------------------

    @Test
    void theOwnerRangeReadIsOrderedByDayAndStopsAtTheBoundaries() {
        row(CheckDayOwnerType.HABIT, ownerId, DAY.minusDays(1), CheckDayOutcome.DONE);
        row(CheckDayOwnerType.HABIT, ownerId, DAY.plusDays(2), CheckDayOutcome.SKIPPED);
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.MISSED);
        row(CheckDayOwnerType.HABIT, ownerId, DAY.plusDays(3), CheckDayOutcome.DONE);
        // A different owner, inside the window — must not leak in.
        row(CheckDayOwnerType.HABIT, UUID.randomUUID(), DAY.plusDays(1), CheckDayOutcome.DONE);

        List<EntityCheckDay> found = entityCheckDayRepository
                .findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                        CheckDayOwnerType.HABIT, ownerId, DAY, DAY.plusDays(2));

        assertThat(found).extracting(EntityCheckDay::getDay)
                .as("both bounds inclusive, oldest first, nothing outside the window")
                .containsExactly(DAY, DAY.plusDays(2));
    }

    @Test
    void theOwnerRangeReadIgnoresTheSameIdUnderADifferentOwnerType() {
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.TASK, ownerId, DAY, CheckDayOutcome.SKIPPED);

        List<EntityCheckDay> found = entityCheckDayRepository
                .findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                        CheckDayOwnerType.TASK, ownerId, DAY, DAY);

        assertThat(found).singleElement()
                .extracting(EntityCheckDay::getOutcome)
                .isEqualTo(CheckDayOutcome.SKIPPED);
    }

    @Test
    void theSingleDayReadReturnsEveryOwnerAlreadyRecordedForThatUser() {
        // This is the diff the day-closing pass runs: what already has a row, so
        // only the missing owners get an absence written.
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.TASK, UUID.randomUUID(), DAY, CheckDayOutcome.MISSED);
        row(CheckDayOwnerType.HABIT, UUID.randomUUID(), DAY.plusDays(1), CheckDayOutcome.DONE);

        List<EntityCheckDay> found = entityCheckDayRepository.findByUserIdAndDay(user.getId(), DAY);

        assertThat(found).hasSize(2)
                .extracting(EntityCheckDay::getOwnerType)
                .containsExactlyInAnyOrder(CheckDayOwnerType.HABIT, CheckDayOwnerType.TASK);
    }

    @Test
    void theUserRangeReadReturnsEveryOwnerTypeTogether() {
        // The export reads one account across a window and wants the lot.
        row(CheckDayOwnerType.HABIT, UUID.randomUUID(), DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.TASK, UUID.randomUUID(), DAY.plusDays(1), CheckDayOutcome.SKIPPED);
        row(CheckDayOwnerType.ROUTINE, UUID.randomUUID(), DAY.plusDays(2), CheckDayOutcome.NOT_SCHEDULED);
        row(CheckDayOwnerType.USER, UUID.randomUUID(), DAY.plusDays(3), CheckDayOutcome.MISSED);
        row(CheckDayOwnerType.HABIT, UUID.randomUUID(), DAY.plusDays(9), CheckDayOutcome.DONE);

        List<EntityCheckDay> found = entityCheckDayRepository
                .findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), DAY, DAY.plusDays(3));

        assertThat(found).extracting(EntityCheckDay::getOwnerType)
                .containsExactly(CheckDayOwnerType.HABIT, CheckDayOwnerType.TASK,
                        CheckDayOwnerType.ROUTINE, CheckDayOwnerType.USER);
    }

    @Test
    void theUserRangeReadNeverCrossesAccounts() {
        User other = new User();
        other.setName("Other");
        other.setEmail("check-day-other-" + UUID.randomUUID() + "@test.com");
        other.setPassword("password123");
        other = userRepository.saveAndFlush(other);
        entityCheckDayRepository.saveAndFlush(new EntityCheckDay(
                other, CheckDayOwnerType.HABIT, UUID.randomUUID(), DAY, CheckDayOutcome.DONE));

        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.SKIPPED);

        List<EntityCheckDay> found = entityCheckDayRepository
                .findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), DAY, DAY);

        assertThat(found).singleElement()
                .extracting(EntityCheckDay::getOwnerId)
                .isEqualTo(ownerId);
    }

    @Test
    void anEmptyRangeReturnsNothingRatherThanFailing() {
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);

        assertThat(entityCheckDayRepository.findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                CheckDayOwnerType.HABIT, ownerId, DAY.plusDays(5), DAY.plusDays(6))).isEmpty();
        assertThat(entityCheckDayRepository.findByUserIdAndDay(user.getId(), DAY.plusDays(5))).isEmpty();
    }

    // --- R8: history outlives the thing it describes ------------------------

    @Test
    void deletingTheOwningHabitLeavesItsHistoryStanding() {
        // owner_id carries no foreign key on purpose: a day's outcome is a fact
        // about that day, not a fact about a row that still exists. Clearing it
        // is an explicit application-level delete, never the database's choice.
        Habit habit = new Habit();
        habit.setName("Read");
        habit.setIconId("ic");
        habit.setImportance(3);
        habit.setDificulty(3);
        habit.setUser(user);
        Habit saved = habitRepository.saveAndFlush(habit);

        row(CheckDayOwnerType.HABIT, saved.getId(), DAY, CheckDayOutcome.DONE);
        habitRepository.delete(saved);

        assertThat(habitRepository.findById(saved.getId())).isEmpty();
        assertThat(entityCheckDayRepository.findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                CheckDayOwnerType.HABIT, saved.getId(), DAY, DAY)).hasSize(1);
    }

    @Test
    void aRowSurvivesForAnOwnerThatNeverExistedInAnyTable() {
        // The same property from the other direction — history recorded through
        // a routine that has since been deleted still inserts and still reads.
        UUID vanishedRoutine = UUID.randomUUID();

        assertThatCode(() -> row(CheckDayOwnerType.ROUTINE, vanishedRoutine, DAY, CheckDayOutcome.NOT_SCHEDULED))
                .doesNotThrowAnyException();
        assertThat(entityCheckDayRepository.findByOwnerTypeAndOwnerIdAndDayBetweenOrderByDayAsc(
                CheckDayOwnerType.ROUTINE, vanishedRoutine, DAY, DAY)).hasSize(1);
    }

    @Test
    void theBulkDeleteClearsOneOwnerAndOnlyThatOwner() {
        UUID otherOwner = UUID.randomUUID();
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.HABIT, ownerId, DAY.plusDays(1), CheckDayOutcome.MISSED);
        row(CheckDayOwnerType.HABIT, otherOwner, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.TASK, ownerId, DAY, CheckDayOutcome.SKIPPED);

        // A bulk @Modifying delete needs the caller's transaction — deliberately
        // not annotated on the repository, so it can never commit on its own
        // half-way through a service that is doing something larger.
        Integer removed = transactionTemplate.execute(status ->
                entityCheckDayRepository.deleteAllByOwner(CheckDayOwnerType.HABIT, ownerId));

        assertThat(removed).isEqualTo(2);
        assertThat(entityCheckDayRepository.findByUserIdAndDay(user.getId(), DAY))
                .extracting(EntityCheckDay::getOwnerType)
                .containsExactlyInAnyOrder(CheckDayOwnerType.HABIT, CheckDayOwnerType.TASK);
    }

    // --- account deletion ----------------------------------------------------

    @Test
    void deletingTheOwningUserSucceedsAndTakesTheHistoryWithIt() {
        row(CheckDayOwnerType.HABIT, ownerId, DAY, CheckDayOutcome.DONE);
        row(CheckDayOwnerType.USER, user.getId(), DAY, CheckDayOutcome.DONE);
        UUID userId = user.getId();

        // The assertion that matters is that the delete itself goes through: a
        // plain foreign key here would abort it, and account deletion would be
        // blocked by a table it does not know about.
        assertThatCode(() -> userRepository.delete(user)).doesNotThrowAnyException();

        assertThat(userRepository.findById(userId)).isEmpty();
        assertThat(entityCheckDayRepository.findByUserIdAndDayBetweenOrderByDayAsc(
                userId, DAY.minusYears(1), DAY.plusYears(1))).isEmpty();
    }

    // --- CheckProgress on rows written before the migration ------------------

    @Test
    void aHabitRowWrittenWithoutTheCheckColumnsReadsBackAsZeroesRatherThanNull() {
        // Exactly what a dev or e2e database holds: rows inserted before V13,
        // which only got the column defaults. If the counters were nullable,
        // Hibernate would hand back a null CheckProgress and the first getter
        // call anywhere would NPE.
        UUID legacyHabitId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> entityManager.createNativeQuery("""
                        INSERT INTO habits
                            (id, user_id, name, icon_id, importance, dificulty, constance,
                             xp, level, actual_level_xp, next_level_xp, created_at, updated_at)
                        VALUES
                            (:id, :userId, 'Legacy habit', 'ic', 3, 3, 0,
                             0, 0, 0, 0, CURRENT_DATE, CURRENT_DATE)
                        """)
                .setParameter("id", legacyHabitId)
                .setParameter("userId", user.getId())
                .executeUpdate());

        Habit legacy = habitRepository.findById(legacyHabitId).orElseThrow();

        assertThat(legacy.getCheckProgress())
                .as("a NULL embeddable here is the failure mode NOT NULL DEFAULT 0 exists to prevent")
                .isNotNull();
        assertThat(legacy.getCheckProgress().getCurrentStreak()).isZero();
        assertThat(legacy.getCheckProgress().getBestStreak()).isZero();
        assertThat(legacy.getCheckProgress().getTotalCheckIns()).isZero();
        assertThat(legacy.getCheckProgress().getFirstCheckInDate()).isNull();
        assertThat(legacy.getCheckProgress().getLastCheckInDate()).isNull();
    }

    @Test
    void checkProgressRoundTripsOnAHabit() {
        Habit habit = new Habit();
        habit.setName("Write");
        habit.setIconId("ic");
        habit.setImportance(2);
        habit.setDificulty(2);
        habit.setUser(user);
        habit.getCheckProgress().setCurrentStreak(4);
        habit.getCheckProgress().setBestStreak(9);
        habit.getCheckProgress().setTotalCheckIns(31);
        habit.getCheckProgress().setFirstCheckInDate(DAY.minusDays(30));
        habit.getCheckProgress().setLastCheckInDate(DAY);
        UUID id = habitRepository.saveAndFlush(habit).getId();

        Habit reloaded = habitRepository.findById(id).orElseThrow();

        assertThat(reloaded.getCheckProgress().getCurrentStreak()).isEqualTo(4);
        assertThat(reloaded.getCheckProgress().getBestStreak()).isEqualTo(9);
        assertThat(reloaded.getCheckProgress().getTotalCheckIns()).isEqualTo(31);
        assertThat(reloaded.getCheckProgress().getFirstCheckInDate()).isEqualTo(DAY.minusDays(30));
        assertThat(reloaded.getCheckProgress().getLastCheckInDate()).isEqualTo(DAY);
    }
}
