package beyou.beyouapp.backend.domain.aiAgent;

import java.util.List;
import java.util.Map;

/**
 * Which frontend domains a WRITE tool touches — travels inside the
 * toolFinished event so the client refetches exactly what changed.
 * Read tools (and context tools, whose data has no UI yet) return empty.
 * Domain names match the frontend Redux slices.
 */
public final class AgentToolDomains {

    private static final Map<String, List<String>> WRITE_TOOLS = Map.ofEntries(
            // Habits
            Map.entry("createUserHabit", List.of("habits")),
            Map.entry("editUserHabit", List.of("habits")),
            Map.entry("deleteUserHabit", List.of("habits")),
            // Categories (habit cards embed category data, so refresh both)
            Map.entry("createUserCategory", List.of("categories")),
            Map.entry("editUserCategory", List.of("categories", "habits")),
            Map.entry("deleteUserCategory", List.of("categories")),
            // Tasks
            Map.entry("createUserTask", List.of("tasks")),
            Map.entry("editUserTask", List.of("tasks")),
            Map.entry("deleteUserTask", List.of("tasks")),
            // Goals (complete awards XP -> perfil; increase/decrease don't)
            Map.entry("createUserGoal", List.of("goals")),
            Map.entry("editUserGoal", List.of("goals")),
            Map.entry("deleteUserGoal", List.of("goals")),
            Map.entry("completeUserGoal", List.of("goals", "perfil")),
            Map.entry("increaseUserGoalValue", List.of("goals")),
            Map.entry("decreaseUserGoalValue", List.of("goals")),
            // Routines
            Map.entry("createUserRoutine", List.of("routines")),
            Map.entry("editUserRoutine", List.of("routines")),
            Map.entry("deleteUserRoutine", List.of("routines")),
            Map.entry("addTaskToRoutineSection", List.of("routines")),
            Map.entry("addHabitToRoutineSection", List.of("routines")),
            Map.entry("removeRoutineItem", List.of("routines")),
            // Check-in (check awards XP -> perfil; skip only marks the item)
            Map.entry("checkRoutineItem", List.of("routines", "perfil")),
            Map.entry("skipRoutineItem", List.of("routines")),
            // Schedules live inside routines on the client — no separate slice.
            Map.entry("createUserSchedule", List.of("routines")),
            Map.entry("updateUserSchedule", List.of("routines")),
            Map.entry("deleteUserSchedule", List.of("routines")),
            // Configuration
            Map.entry("updateUserConfiguration", List.of("perfil")));

    private AgentToolDomains() {}

    public static List<String> domainsOf(String toolName) {
        return WRITE_TOOLS.getOrDefault(toolName, List.of());
    }
}
