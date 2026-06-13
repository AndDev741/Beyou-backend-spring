package beyou.beyouapp.backend.domain.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.exceptions.ai.AiGenerationException;
import lombok.RequiredArgsConstructor;

/**
 * Serializes a compact summary of the user's existing categories/habits/tasks
 * for the prompt, so the AI can reuse them by UUID instead of proposing
 * duplicates. Caps bound token cost; descriptions are truncated.
 */
@Component
@RequiredArgsConstructor
public class AiUserContextBuilder {

    private static final int MAX_ITEMS_PER_TYPE = 100;
    private static final int MAX_DESCRIPTION_LENGTH = 120;

    // Local instance: Spring Boot 4 auto-configures Jackson 3 (tools.jackson),
    // so there is no com.fasterxml ObjectMapper bean to inject. This mapper
    // only serializes the prompt context (UUIDs + strings) — any JSON works.
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HabitRepository habitRepository;
    private final TaskRepository taskRepository;
    private final CategoryRepository categoryRepository;

    public record ItemSummary(UUID id, String name, String description) {}
    public record UserContext(List<ItemSummary> categories, List<ItemSummary> habits, List<ItemSummary> tasks) {}

    public String build(UUID userId) {
        List<ItemSummary> categories = categoryRepository.findAllByUserId(userId)
                .orElse(new ArrayList<>()).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .map(c -> new ItemSummary(c.getId(), c.getName(), truncate(c.getDescription())))
                .toList();
        List<ItemSummary> habits = habitRepository.findAllByUserId(userId).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .map(h -> new ItemSummary(h.getId(), h.getName(), truncate(h.getDescription())))
                .toList();
        List<ItemSummary> tasks = taskRepository.findAllByUserId(userId)
                .orElse(new ArrayList<>()).stream()
                .limit(MAX_ITEMS_PER_TYPE)
                .map(t -> new ItemSummary(t.getId(), t.getName(), truncate(t.getDescription())))
                .toList();
        try {
            return OBJECT_MAPPER.writeValueAsString(new UserContext(categories, habits, tasks));
        } catch (JsonProcessingException e) {
            throw new AiGenerationException("Failed to serialize user context", e);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_DESCRIPTION_LENGTH ? value : value.substring(0, MAX_DESCRIPTION_LENGTH);
    }
}
