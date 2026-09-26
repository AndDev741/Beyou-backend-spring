package beyou.beyouapp.backend.unit.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryMapper;
import beyou.beyouapp.backend.domain.category.dto.CategoryResponseDTO;
import beyou.beyouapp.backend.domain.goal.Goal;

/**
 * The category card lists what the category is working towards now. An archived goal keeps its
 * link to the category, so restoring it brings the categories back, but it drops off the card.
 */
class CategoryMapperArchivedGoalsTest {

    private final CategoryMapper mapper = new CategoryMapper();

    @Test
    void anArchivedGoalStaysLinkedButIsNotListed() {
        Goal active = goal("Run a marathon", null);
        Goal archived = goal("Learn the ukulele", Instant.parse("2026-09-20T08:00:00Z"));
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Health");
        category.setHabits(new ArrayList<>());
        category.setTasks(new ArrayList<>());
        category.setGoals(new ArrayList<>(List.of(active, archived)));

        CategoryResponseDTO dto = mapper.toResponseDTO(category);

        assertThat(dto.goals()).containsOnlyKeys(active.getId());
        assertThat(category.getGoals()).hasSize(2);
    }

    private static Goal goal(String name, Instant archivedAt) {
        Goal g = new Goal();
        g.setId(UUID.randomUUID());
        g.setName(name);
        g.setArchivedAt(archivedAt);
        return g;
    }
}
