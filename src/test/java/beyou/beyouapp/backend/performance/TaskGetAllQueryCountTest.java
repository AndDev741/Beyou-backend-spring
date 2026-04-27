package beyou.beyouapp.backend.performance;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.HibernateStatistics;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.task.Task;
import beyou.beyouapp.backend.domain.task.TaskService;
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
 * Regression guard for N+1 in {@code TaskService.getAllTasks()}.
 *
 * <p>Before fix: 1 + N statements (one extra SELECT per task to load categories).
 * After fix (@EntityGraph on findAllByUserId): bounded constant.
 */
@Transactional
class TaskGetAllQueryCountTest extends AbstractIntegrationTest {

    private static final int TASK_COUNT = 10;

    @Autowired private EntityManagerFactory emf;
    @PersistenceContext private EntityManager em;
    @Autowired private UserRepository userRepository;
    @Autowired private TaskService taskService;

    @Test
    @DisplayName("getAllTasks fetches tasks + categories in bounded queries")
    void getAllTasks_isBoundedRegardlessOfTaskCount() {
        User user = seedUser();
        List<Category> categories = seedCategories(user, 3);
        for (int i = 0; i < TASK_COUNT; i++) {
            seedTask(user, "task-" + i, categories);
        }
        em.flush();
        em.clear();

        var stats = new HibernateStatistics(emf);

        var result = taskService.getAllTasks(user.getId());

        assertThat(result).hasSize(TASK_COUNT);
        assertThat(stats.statementCount())
                .as("Should be bounded — N+1 means ~%d. Stats: %s", TASK_COUNT + 1, stats)
                .isLessThanOrEqualTo(5);

        System.out.println("[N+1 fix] TaskService.getAllTasks with " + TASK_COUNT + " tasks → " + stats);
    }

    private User seedUser() {
        User user = new User();
        user.setName("Task Tester");
        user.setEmail("task-query-test@example.com");
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
}
