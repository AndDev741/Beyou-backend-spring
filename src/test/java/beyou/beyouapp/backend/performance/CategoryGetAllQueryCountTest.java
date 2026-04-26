package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.task.Task;
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
 * Demonstrates and prevents the N+1 pattern in
 * {@code CategoryService.getAllCategories()}.
 *
 * <p>Category has THREE lazy {@code @ManyToMany} bag collections — habits,
 * tasks, goals — accessed by the mapper. Without batching, fetching N
 * categories produces 1 + 3N statements. With {@code @BatchSize}, it collapses
 * to 1 + 3 (one batched fetch per relationship type, regardless of N).
 *
 * <p>{@code @EntityGraph} is NOT an option here: trying to JOIN FETCH any two
 * of the three List collections throws {@code MultipleBagFetchException}.
 */
@Transactional
class CategoryGetAllQueryCountTest extends AbstractIntegrationTest {

    private static final int CATEGORY_COUNT = 5;
    private static final int CHILDREN_PER_CATEGORY = 3;

    @Autowired
    private EntityManagerFactory emf;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryService categoryService;

    @Test
    @DisplayName("getAllCategories should fetch N categories + their 3 collections in a bounded number of queries")
    void getAllCategories_isBoundedRegardlessOfCategoryCount() {
        // Arrange — seed user, categories, plus habits/tasks/goals each linked to all categories
        User user = seedUser();
        List<Category> categories = seedCategories(user, CATEGORY_COUNT);
        for (int i = 0; i < CHILDREN_PER_CATEGORY; i++) {
            seedHabit(user, "habit-" + i, categories);
            seedTask(user, "task-" + i, categories);
            seedGoal(user, "goal-" + i, categories);
        }
        em.flush();
        em.clear();

        var stats = new HibernateStatistics(emf);

        // Act
        var result = categoryService.getAllCategories(user.getId());

        // Assert correctness
        assertThat(result).hasSize(CATEGORY_COUNT);
        assertThat(result.get(0).habits()).hasSize(CHILDREN_PER_CATEGORY);
        assertThat(result.get(0).tasks()).hasSize(CHILDREN_PER_CATEGORY);
        assertThat(result.get(0).goals()).hasSize(CHILDREN_PER_CATEGORY);

        // Assert performance.
        // Without @BatchSize: 1 + 3*N = 16 statements for N=5.
        // With @BatchSize: 1 (categories) + 3 (one batch per collection type) = 4.
        // Threshold of 6 covers small overhead.
        assertThat(stats.statementCount())
                .as("Should be bounded — N+1 would mean ~%d statements. Stats: %s",
                        1 + 3 * CATEGORY_COUNT, stats)
                .isLessThanOrEqualTo(6);

        System.out.println("[N+1 fix] CategoryService.getAllCategories with "
                + CATEGORY_COUNT + " categories × " + CHILDREN_PER_CATEGORY + " children each → " + stats);
    }

    // --- seed helpers ---

    private User seedUser() {
        User user = new User();
        user.setName("Category Query Tester");
        user.setEmail("category-query-test@example.com");
        user.setPassword("placeholder");
        user.setCreatedAt(Date.valueOf(Instant.now().atZone(ZoneOffset.UTC).toLocalDate()));
        return userRepository.saveAndFlush(user);
    }

    private List<Category> seedCategories(User user, int count) {
        List<Category> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Category c = new Category();
            c.setName("category-" + i);
            c.setDescription("seed");
            c.setIconId("icon-" + i);
            c.setUser(user);
            c.setCreatedAt(Date.valueOf(LocalDate.now()));
            em.persist(c);
            result.add(c);
        }
        return result;
    }

    private void seedHabit(User user, String name, List<Category> categories) {
        Habit h = new Habit();
        h.setName(name);
        h.setDescription("seed");
        h.setIconId("icon");
        h.setImportance(1);
        h.setDificulty(1);
        h.setUser(user);
        h.setCategories(new ArrayList<>(categories));
        em.persist(h);
    }

    private void seedTask(User user, String name, List<Category> categories) {
        Task t = new Task();
        t.setName(name);
        t.setDescription("seed");
        t.setIconId("icon");
        t.setImportance(1);
        t.setDificulty(1);
        t.setUser(user);
        t.setCategories(new ArrayList<>(categories));
        em.persist(t);
    }

    private void seedGoal(User user, String name, List<Category> categories) {
        Goal g = new Goal();
        g.setName(name);
        g.setIconId("icon");
        g.setDescription("seed");
        g.setTargetValue(100.0);
        g.setUnit("units");
        g.setCurrentValue(0.0);
        g.setComplete(false);
        g.setStartDate(LocalDate.now());
        g.setEndDate(LocalDate.now().plusDays(30));
        g.setXpReward(10.0);
        g.setStatus(GoalStatus.NOT_STARTED);
        g.setTerm(GoalTerm.SHORT_TERM);
        g.setUser(user);
        g.setCategories(new ArrayList<>(categories));
        em.persist(g);
    }
}
