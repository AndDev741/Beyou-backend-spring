package beyou.beyouapp.backend.integration.goal;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryService;
import beyou.beyouapp.backend.domain.category.dto.CategoryRequestDTO;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevelRepository;
import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.goal.GoalService;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The AI agent's tools run inside the SSE Flux, on a reactor thread — not the servlet
 * request thread that Open Session In View binds an EntityManager to. So every service
 * method the agent reaches has to hold its own session.
 *
 * NOT_SUPPORTED propagation reproduces that: no ambient transaction, no OSIV, which is
 * exactly what increaseUserGoalValue/decreaseUserGoalValue see in production. The
 * existing coverage cannot see this — goalServiceUnitTest mocks the repository (so
 * categories is a plain ArrayList, never a Hibernate proxy) and goals.spec.ts drives
 * HTTP, where OSIV holds a session open for the whole request.
 */
class GoalValueOutsideSessionTest extends AbstractIntegrationTest {

    @Autowired private GoalService goalService;
    @Autowired private GoalRepository goalRepository;
    @Autowired private CategoryService categoryService;
    @Autowired private UserRepository userRepository;
    @Autowired private XpByLevelRepository xpByLevelRepository;

    private User user;
    private UUID goalId;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setName("Goal Session IT User");
        user.setEmail("goal-session-" + UUID.randomUUID() + "@test.com");
        user.setPassword("password123");
        user = userRepository.saveAndFlush(user);
        if (xpByLevelRepository.findByLevel(0) == null) xpByLevelRepository.save(new XpByLevel(0, 0));
        if (xpByLevelRepository.findByLevel(1) == null) xpByLevelRepository.save(new XpByLevel(1, 100));

        Category category = categoryService.createCategoryEntity(
                new CategoryRequestDTO("Health", "ic", null, ExperienceLevel.BEGINNER), user);

        goalService.createGoal(new CreateGoalRequestDTO(
                        "Ler 12 livros", null, "ic", 12.0, "livros", 0.0,
                        List.of(category.getId()), null,
                        LocalDate.now(), LocalDate.now().plusMonths(6),
                        GoalStatus.NOT_STARTED, GoalTerm.LONG_TERM, null),
                user.getId());

        goalId = goalRepository.findAll().stream()
                .filter(g -> g.getUser().getId().equals(user.getId()))
                .findFirst().orElseThrow().getId();
    }

    /** The reported failure: agent increments a goal, no session, categories is lazy. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void increaseOutsideAnyTransactionOrRequest() {
        GoalResponseDTO dto = goalService.increaseCurrentValue(goalId, 3.0, user.getId());

        assertEquals(3.0, dto.currentValue(), "the increment is applied");
        assertEquals(1, dto.categories().size(), "the lazy category collection is resolved");
        assertEquals(GoalStatus.IN_PROGRESS, dto.status(), "first progress starts the goal");

        cleanUp();
    }

    /** Same shape on the way down. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decreaseOutsideAnyTransactionOrRequest() {
        goalService.increaseCurrentValue(goalId, 5.0, user.getId());
        GoalResponseDTO dto = goalService.decreaseCurrentValue(goalId, 2.0, user.getId());

        assertEquals(3.0, dto.currentValue());
        assertEquals(1, dto.categories().size(), "the lazy category collection is resolved");

        cleanUp();
    }

    /** The floor guard still holds when the read actually reaches the database. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void decreaseNeverGoesBelowZeroOutsideASession() {
        GoalResponseDTO dto = goalService.decreaseCurrentValue(goalId, 99.0, user.getId());

        assertEquals(0.0, dto.currentValue(), "floored at zero, not negative");

        cleanUp();
    }

    private void cleanUp() {
        try {
            goalService.deleteGoal(goalId, user.getId());
            userRepository.delete(user);
        } catch (RuntimeException ignored) {
            // best effort — the assertions above are the point
        }
    }
}
