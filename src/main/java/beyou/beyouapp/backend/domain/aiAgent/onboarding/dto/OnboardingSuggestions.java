package beyou.beyouapp.backend.domain.aiAgent.onboarding.dto;

import java.util.List;

/** Envelope returned to the client: only the field for the requested step is non-null. */
public record OnboardingSuggestions(
        List<CategorySuggestion> categories,
        List<HabitSuggestion> habits,
        List<TaskSuggestion> tasks,
        RoutineSuggestion routine,
        List<GoalSuggestion> goals) {

    public record CategorySuggestion(String name, String description, String iconId) {}

    public record HabitSuggestion(String name, String description, String motivationalPhrase,
            String iconId, String categoryName, Integer importance, Integer difficulty) {}

    public record TaskSuggestion(String name, String description, String iconId,
            String categoryName, Integer importance, Integer difficulty) {}

    public record ItemPlacement(String name, String startTime, String endTime) {}

    public record SectionSuggestion(String name, String iconId, String startTime, String endTime,
            List<ItemPlacement> habits, List<ItemPlacement> tasks) {}

    public record RoutineSuggestion(String name, String iconId, List<String> scheduleDays,
            List<SectionSuggestion> sections) {}

    public record GoalSuggestion(String name, String description, String iconId, String categoryName,
            Double targetValue, String unit, String motivation, String term, Integer durationDays) {}

    // BeanOutputConverter targets (the LLM returns these; the service wraps them into the envelope)
    public record CategoriesPayload(List<CategorySuggestion> categories) {}
    public record HabitsTasksPayload(List<HabitSuggestion> habits, List<TaskSuggestion> tasks) {}
    public record GoalsPayload(List<GoalSuggestion> goals) {}
}
