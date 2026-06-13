package beyou.beyouapp.backend.unit.ai;

import beyou.beyouapp.backend.domain.ai.AiUserContextBuilder;
import beyou.beyouapp.backend.domain.category.Category;
import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.habit.Habit;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUserContextBuilderTest {

    @Mock private HabitRepository habitRepository;
    @Mock private TaskRepository taskRepository;
    @Mock private CategoryRepository categoryRepository;

    @Test
    void buildsCompactJsonWithIdsAndNames() throws Exception {
        UUID userId = UUID.randomUUID();
        Habit habit = new Habit();
        habit.setId(UUID.randomUUID());
        habit.setName("Read 30min");
        habit.setDescription("x".repeat(500)); // must be truncated to 120

        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Health");

        when(habitRepository.findAllByUserId(userId)).thenReturn(new ArrayList<>(List.of(habit)));
        when(taskRepository.findAllByUserId(userId)).thenReturn(Optional.of(new ArrayList<>()));
        when(categoryRepository.findAllByUserId(userId)).thenReturn(Optional.of(new ArrayList<>(List.of(category))));

        AiUserContextBuilder builder = new AiUserContextBuilder(
                habitRepository, taskRepository, categoryRepository);
        String json = builder.build(userId);

        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals("Read 30min", root.get("habits").get(0).get("name").asText());
        assertEquals(habit.getId().toString(), root.get("habits").get(0).get("id").asText());
        assertEquals(120, root.get("habits").get(0).get("description").asText().length());
        assertEquals("Health", root.get("categories").get(0).get("name").asText());
        assertEquals(0, root.get("tasks").size());
    }
}
