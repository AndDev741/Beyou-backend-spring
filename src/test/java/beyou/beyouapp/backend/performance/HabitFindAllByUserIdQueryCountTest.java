package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates and prevents the classic N+1 pattern in
 * {@code HabitService.getHabits()}.
 *
 * <p>Without an {@code @EntityGraph} or {@code JOIN FETCH}, fetching N habits
 * and accessing each habit's {@code categories} collection produces 1 + N
 * SELECT statements. With the fix, the count drops to a small constant.
 *
 * <p>This test asserts the <b>fixed</b> state. Running it before the fix should
 * fail with a statement count proportional to {@link #HABIT_COUNT}.
 */
@Transactional
class HabitFindAllByUserIdQueryCountTest extends AbstractIntegrationTest {

    /**
     * High enough that 1+N is dramatically larger than the bounded constant
     * we want to assert (≤ 5). Low enough to keep test fast.
     */
    private static final int HABIT_COUNT = 10;

    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private HabitService habitService;

    @Test
    @DisplayName("getHabits should fetch N habits + their categories in a bounded number of queries")
    void getHabits_isBoundedRegardlessOfHabitCount() {
        // Arrange — seed user, categories, and habits
        User user = seedUser();
        List<Category> categories = seedCategories(user, 3);
        for (int i = 0; i < HABIT_COUNT; i++) {
            seedHabitWithCategories(user, "habit-" + i, categories);
        }
        em.flush();
        em.clear(); // discard L1 cache so reads hit the DB cold

        var stats = new HibernateStatistics(emf);

        // Act — same call path the controller uses in production
        var result = habitService.getHabits(user.getId());

        // Assert correctness
        assertThat(result).hasSize(HABIT_COUNT);
        assertThat(result.get(0).categories()).hasSize(3);

        // Assert performance
        // The fix should produce a small, bounded number of queries — NOT 1 + N.
        // Threshold of 5 covers: habits query, categories join/fetch, plus
        // any small fixed overhead (e.g. user lookup if cached).
        assertThat(stats.statementCount())
                .as("Should be bounded — N+1 would mean ~%d statements. Stats: %s",
                        HABIT_COUNT + 1, stats)
                .isLessThanOrEqualTo(5);

        // Visible in CI logs: confirms current cost
        System.out.println("[N+1 fix] HabitService.getHabits with " + HABIT_COUNT + " habits → " + stats);
    }

    // --- seed helpers ---

    private User seedUser() {
        User user = new User();
        user.setName("Query Count Tester");
        user.setEmail("query-count-test@example.com");
        user.setPassword("placeholder-not-validated");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        return userRepository.saveAndFlush(user);
    }

    private List<Category> seedCategories(User user, int count) {
        List<Category> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Category category = new Category();
            category.setName("category-" + i);
            category.setDescription("seed");
            category.setIconId("icon-" + i);
            category.setUser(user);
            category.setCreatedAt(Date.valueOf(LocalDate.now()));
            em.persist(category);
            result.add(category);
        }
        return result;
    }

    private void seedHabitWithCategories(User user, String name, List<Category> categories) {
        Habit habit = new Habit();
        habit.setName(name);
        habit.setDescription("seed");
        habit.setIconId("icon");
        habit.setImportance(1);
        habit.setDificulty(1);
        habit.setUser(user);
        habit.setCategories(new ArrayList<>(categories));
        em.persist(habit);
    }
}
