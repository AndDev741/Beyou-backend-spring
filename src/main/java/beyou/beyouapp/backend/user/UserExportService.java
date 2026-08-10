package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.domain.category.CategoryRepository;
import beyou.beyouapp.backend.domain.checkday.CheckHistoryService;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDay;
import beyou.beyouapp.backend.domain.checkday.EntityCheckDayRepository;
import beyou.beyouapp.backend.domain.common.UserDateResolver;
import beyou.beyouapp.backend.domain.feedback.FeedbackService;
import beyou.beyouapp.backend.domain.goal.GoalRepository;
import beyou.beyouapp.backend.domain.habit.HabitRepository;
import beyou.beyouapp.backend.domain.task.TaskRepository;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final FeedbackService feedbackService;
    private final EntityCheckDayRepository entityCheckDayRepository;

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

        // Feedback (R21) — submissions, the replies they got back, and
        // references to any attached images. Assembled by the feedback domain
        // itself; the shape of a submission is not this class's business.
        export.put("feedback", feedbackService.exportForUser(userId));

        // Check-in history (R10)
        export.put("checkHistory", checkHistory(user));

        return export;
    }

    /**
     * R10 — the per-day outcome history, grouped by the thing it describes, bounded on
     * purpose.
     *
     * <p>The bound is the point. Every other section here has a natural ceiling — a user has
     * so many habits, so many submissions — but this one gains a row per checkable entity per
     * day and never stops, and the whole payload is assembled in memory inside one read-only
     * transaction. An account three years old would put roughly a thousand days times every
     * habit it ever had into a single map. So the window is the most recent
     * {@link CheckHistoryService#MAX_RANGE_DAYS} days, the same cap the history endpoint
     * clamps to, and the export names it: {@code from}, {@code to} and {@code maxRangeDays}
     * are in the payload so a reader can see what was covered instead of assuming the file is
     * everything. Rows outside the window are still stored — nothing here deletes them — and
     * the endpoint can walk further back a window at a time.
     *
     * <p>{@code to} is today in the ACCOUNT's timezone (R15), so the last day in the export
     * is the day the user believes it is.
     *
     * <p>Rows come back ordered by day across every owner type together, so grouping them
     * into first-seen owner order leaves each owner's days ascending without a second sort.
     */
    private Map<String, Object> checkHistory(User user) {
        LocalDate to = UserDateResolver.today(user);
        LocalDate from = to.minusDays(CheckHistoryService.MAX_RANGE_DAYS - 1L);

        List<EntityCheckDay> rows = entityCheckDayRepository
                .findByUserIdAndDayBetweenOrderByDayAsc(user.getId(), from, to);

        Map<String, Map<String, Object>> byOwner = new LinkedHashMap<>();
        for (EntityCheckDay row : rows) {
            String key = row.getOwnerType() + ":" + row.getOwnerId();
            Map<String, Object> owner = byOwner.computeIfAbsent(key, k -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("ownerType", row.getOwnerType());
                created.put("ownerId", row.getOwnerId());
                created.put("days", new ArrayList<Map<String, Object>>());
                return created;
            });

            Map<String, Object> day = new LinkedHashMap<>();
            day.put("day", row.getDay());
            day.put("outcome", row.getOutcome());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> days = (List<Map<String, Object>>) owner.get("days");
            days.add(day);
        }

        Map<String, Object> history = new LinkedHashMap<>();
        history.put("from", from);
        history.put("to", to);
        history.put("maxRangeDays", CheckHistoryService.MAX_RANGE_DAYS);
        history.put("note", "Covers the most recent " + CheckHistoryService.MAX_RANGE_DAYS
                + " days only. Anything older is still stored and readable through the "
                + "check-history endpoint one window at a time.");
        history.put("owners", List.copyOf(byOwner.values()));
        return history;
    }
}
