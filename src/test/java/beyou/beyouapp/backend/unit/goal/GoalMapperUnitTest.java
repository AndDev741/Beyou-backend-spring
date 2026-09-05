package beyou.beyouapp.backend.unit.goal;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalMapper;
import beyou.beyouapp.backend.domain.goal.GoalStatus;
import beyou.beyouapp.backend.domain.goal.GoalTerm;
import beyou.beyouapp.backend.domain.goal.dto.CreateGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.EditGoalRequestDTO;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import beyou.beyouapp.backend.user.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces Bug 5: a goal associated with the SAME category more than once
 * (a duplicate goal_category join row, which the {@code @ManyToMany List}
 * allows) blew up {@code toResponseDTO} because {@code Collectors.toMap} throws
 * {@code IllegalStateException: Duplicate key} on a repeated key.
 *
 * The duplicate originated from the UI sending the same id twice in
 * {@code categoriesId}. The mapper must be resilient to it.
 */
public class GoalMapperUnitTest {

    private Category category(UUID id, String name, String iconId) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setIconId(iconId);
        return category;
    }

    @Test
    void toResponseDTO_collapsesDuplicateCategoryAssociations() {
        GoalMapper mapper = new GoalMapper();

        UUID categoryId = UUID.randomUUID();
        Category duplicated = category(categoryId, "Qualidade de Vida", "ri:md/MdOutlineFilterVintage");

        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        goal.setName("Regularizar-me em Portugal");
        // Same category twice — exactly what the buggy create payload produced.
        goal.setCategories(new ArrayList<>(List.of(duplicated, duplicated)));

        GoalResponseDTO dto = mapper.toResponseDTO(goal);

        assertEquals(1, dto.categories().size(),
                "duplicate category associations must collapse to a single entry");
        assertEquals("Qualidade de Vida", dto.categories().get(categoryId).name());
    }

    /**
     * `complete` and a COMPLETED status say the same thing, and only
     * PUT /goal/complete may change it — that is the one path that moves XP. The
     * three tests below pin the invariant at both write points: a form that asks
     * for COMPLETED gets an in-progress goal, and an edit carries whatever
     * completion the goal already had.
     */
    private CreateGoalRequestDTO createDTO(GoalStatus status) {
        return new CreateGoalRequestDTO(
                "Read seven chapters", "one a day", "lucide:book", 7.0, "chapters", 0.0,
                List.of(), "learn", LocalDate.now(), LocalDate.now().plusDays(7),
                status, GoalTerm.SHORT_TERM, null);
    }

    private EditGoalRequestDTO editDTO(boolean complete, GoalStatus status) {
        return new EditGoalRequestDTO(
                UUID.randomUUID(), "Read seven chapters", "lucide:book", "one a day", 7.0,
                "chapters", 3.0, complete, List.of(), "learn", LocalDate.now(),
                LocalDate.now().plusDays(7), status, GoalTerm.SHORT_TERM, null);
    }

    @Test
    void toEntity_neverCreatesAGoalAlreadyCompleted() {
        Goal goal = new GoalMapper().toEntity(createDTO(GoalStatus.COMPLETED), List.of(), new User());

        assertEquals(GoalStatus.IN_PROGRESS, goal.getStatus());
        assertEquals(false, goal.getComplete());
    }

    @Test
    void updateEntity_cannotCompleteAGoal() {
        Goal goal = new Goal();
        goal.setComplete(false);
        goal.setStatus(GoalStatus.IN_PROGRESS);

        new GoalMapper().updateEntity(goal, editDTO(true, GoalStatus.COMPLETED), List.of());

        assertEquals(false, goal.getComplete());
        assertEquals(GoalStatus.IN_PROGRESS, goal.getStatus());
    }

    @Test
    void updateEntity_cannotUncompleteAGoal() {
        Goal goal = new Goal();
        goal.setComplete(true);
        goal.setStatus(GoalStatus.COMPLETED);

        new GoalMapper().updateEntity(goal, editDTO(false, GoalStatus.NOT_STARTED), List.of());

        assertEquals(true, goal.getComplete());
        assertEquals(GoalStatus.COMPLETED, goal.getStatus());
    }

    @Test
    void updateEntity_movesAnOpenGoalBetweenTheOpenStatuses() {
        Goal goal = new Goal();
        goal.setComplete(false);
        goal.setStatus(GoalStatus.IN_PROGRESS);

        new GoalMapper().updateEntity(goal, editDTO(false, GoalStatus.NOT_STARTED), List.of());

        assertEquals(GoalStatus.NOT_STARTED, goal.getStatus());
    }

    @Test
    void toResponseDTO_exposesTheParentIdWithoutTouchingTheRelation() {
        Goal goal = new Goal();
        goal.setId(UUID.randomUUID());
        UUID parentId = UUID.randomUUID();
        goal.setParentId(parentId);
        // `parent` deliberately left null: the mapper must read the mirrored column, which
        // is what keeps GET /goal at one query whatever the tree looks like.

        GoalResponseDTO dto = new GoalMapper().toResponseDTO(goal);

        assertEquals(parentId, dto.parentId());
    }
}
