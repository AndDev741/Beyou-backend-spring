package beyou.beyouapp.backend.unit.goal;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.goal.Goal;
import beyou.beyouapp.backend.domain.goal.GoalMapper;
import beyou.beyouapp.backend.domain.goal.dto.GoalResponseDTO;
import org.junit.jupiter.api.Test;

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
}
