package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
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
 * Regression guard for N+1 in {@code GoalService.getAllGoals()}.
 *
 * <p>Before fix: 1 + N statements (one extra SELECT per goal to load categories).
 * After fix (@EntityGraph on findAllByUserId): bounded constant.
 */
@Transactional
class GoalGetAllQueryCountTest extends AbstractIntegrationTest {

    private static final int GOAL_COUNT = 10;

    @Autowired private EntityManagerFactory emf;
    @PersistenceContext private EntityManager em;
    @Autowired private UserRepository userRepository;
    @Autowired private GoalService goalService;

    @Test
    @DisplayName("getAllGoals fetches goals + categories in bounded queries")
    void getAllGoals_isBoundedRegardlessOfGoalCount() {
        User user = seedUser();
        List<Category> categories = seedCategories(user, 3);
        for (int i = 0; i < GOAL_COUNT; i++) {
            seedGoal(user, "goal-" + i, categories);
        }
        em.flush();
        em.clear();

        var stats = new HibernateStatistics(emf);

        var result = goalService.getAllGoals(user.getId());

        assertThat(result).hasSize(GOAL_COUNT);
        assertThat(stats.statementCount())
                .as("Should be bounded — N+1 means ~%d. Stats: %s", GOAL_COUNT + 1, stats)
                .isLessThanOrEqualTo(5);

        System.out.println("[N+1 fix] GoalService.getAllGoals with " + GOAL_COUNT + " goals → " + stats);
    }

    private User seedUser() {
        User user = new User();
        user.setName("Goal Tester");
        user.setEmail("goal-query-test@example.com");
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
            c.setIconId("icon");
            c.setUser(user);
            c.setCreatedAt(Date.valueOf(LocalDate.now()));
            em.persist(c);
            result.add(c);
        }
        return result;
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
