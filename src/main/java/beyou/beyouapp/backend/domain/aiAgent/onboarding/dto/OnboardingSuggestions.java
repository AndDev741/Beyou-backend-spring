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

    /**
     * A routine plan, plus anything it needs that the user does not have yet.
     *
     * Placements name their item and nothing else, so the plan can only reach an item
     * that already exists. That used to be the whole contract, and the prompt said so:
     * build the day out of the accepted habits and tasks, invent nothing. It made the
     * routine step the one place in the wizard where the assistant could see a gap in
     * someone's day and not fill it — and the client dropped any name it could not
     * resolve, silently, so a model that ignored the instruction produced a routine
     * quietly missing a step.
     *
     * {@code newHabits} and {@code newTasks} are the way to fill it. They carry the
     * same shape the HABITS_TASKS step returns, so the client creates them with the
     * helper it already has, and the placements go on referring to everything by name.
     */
    public record RoutineSuggestion(String name, String iconId, List<String> scheduleDays,
            List<SectionSuggestion> sections,
            List<HabitSuggestion> newHabits, List<TaskSuggestion> newTasks) {}

    public record GoalSuggestion(String name, String description, String iconId, String categoryName,
            Double targetValue, String unit, String motivation, String term, Integer durationDays) {}

    // BeanOutputConverter targets (the LLM returns these; the service wraps them into the envelope)
    public record CategoriesPayload(List<CategorySuggestion> categories) {}
    public record HabitsTasksPayload(List<HabitSuggestion> habits, List<TaskSuggestion> tasks) {}
    public record GoalsPayload(List<GoalSuggestion> goals) {}
}
