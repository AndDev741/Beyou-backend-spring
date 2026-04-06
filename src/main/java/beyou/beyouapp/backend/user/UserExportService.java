package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserExportService {

    private final AuthenticatedUser authenticatedUser;
    private final CategoryRepository categoryRepository;
    private final HabitRepository habitRepository;
    private final GoalRepository goalRepository;
    private final TaskRepository taskRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> exportUserData() {
        User user = authenticatedUser.getAuthenticatedUser();
        UUID userId = user.getId();

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", Instant.now().toString());

        // Profile
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", user.getName());
        profile.put("email", user.getEmail());
        profile.put("photo", user.getPerfilPhoto());
        profile.put("createdAt", user.getCreatedAt());
        profile.put("isGoogleAccount", user.isGoogleAccount());
        export.put("profile", profile);

        // Categories
        var categories = categoryRepository.findAllByUserId(userId).orElse(new java.util.ArrayList<>());
        export.put("categories", categories.stream().map(c -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", c.getId());
            map.put("name", c.getName());
            map.put("iconId", c.getIconId());
            map.put("description", c.getDescription());
            return map;
        }).toList());

        // Habits
        var habits = habitRepository.findAllByUserId(userId);
        export.put("habits", habits.stream().map(h -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", h.getId());
            map.put("name", h.getName());
            map.put("description", h.getDescription());
            map.put("importance", h.getImportance());
            map.put("difficulty", h.getDificulty());
            return map;
        }).toList());

        // Goals
        var goals = goalRepository.findAllByUserId(userId).orElse(List.of());
        export.put("goals", goals.stream().map(g -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", g.getId());
            map.put("name", g.getName());
            map.put("description", g.getDescription());
            map.put("targetValue", g.getTargetValue());
            map.put("currentValue", g.getCurrentValue());
            map.put("status", g.getStatus());
            map.put("startDate", g.getStartDate());
            map.put("endDate", g.getEndDate());
            return map;
        }).toList());

        // Tasks
        var tasks = taskRepository.findAllByUserId(userId).orElse(List.of());
        export.put("tasks", tasks.stream().map(t -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", t.getId());
            map.put("name", t.getName());
            map.put("description", t.getDescription());
            map.put("importance", t.getImportance());
            map.put("difficulty", t.getDificulty());
            return map;
        }).toList());

        return export;
    }
}
