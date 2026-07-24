package beyou.beyouapp.backend.domain.aiAgent.onboarding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import beyou.beyouapp.backend.domain.aiAgent.AiIconCatalog;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestionRequest;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestionRequest.OnboardingContext;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestions;
import beyou.beyouapp.backend.domain.aiAgent.onboarding.dto.OnboardingSuggestions.*;
import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OnboardingSuggestionService {

    private static final int MAX_ITEMS = 20;

    private final ChatClient chatClient;
    private final Resource systemTemplate;

    public OnboardingSuggestionService(ChatModel chatModel,
            @Value("classpath:/prompts/onboarding.st") Resource systemTemplate) {
        // No advisors, no tools, no memory: one stateless structured call per request.
        this.chatClient = ChatClient.builder(chatModel).build();
        this.systemTemplate = systemTemplate;
    }

    public OnboardingSuggestions suggest(OnboardingSuggestionRequest request, User user) {
        return switch (request.step()) {
            case CATEGORIES -> {
                List<String> names = Optional.ofNullable(request.categoryNames()).orElse(List.of());
                CategoriesPayload payload = call(CategoriesPayload.class, categoriesMessage(names), user);
                yield new OnboardingSuggestions(verbatimCategories(names, payload), null, null, null, null);
            }
            case HABITS_TASKS -> {
                HabitsTasksPayload payload = call(HabitsTasksPayload.class,
                        habitsTasksMessage(request.context(), request.newRequest()), user);
                yield new OnboardingSuggestions(null,
                        sanitizeHabits(payload.habits()), sanitizeTasks(payload.tasks()), null, null);
            }
            case ROUTINE -> {
                RoutineSuggestion routine = call(RoutineSuggestion.class,
                        routineMessage(request.context()), user);
                yield new OnboardingSuggestions(null, null, null, sanitizeRoutine(routine), null);
            }
            case GOALS -> {
                GoalsPayload payload = call(GoalsPayload.class,
                        goalsMessage(request.context(), request.newRequest()), user);
                yield new OnboardingSuggestions(null, null, null, null, sanitizeGoals(payload.goals()));
            }
        };
    }

    // ---- LLM call with one retry, then AI_UNAVAILABLE ----

    private <T> T call(Class<T> type, String userMessage, User user) {
        try {
            return doCall(type, userMessage, user);
        } catch (RuntimeException first) {
            log.warn("Onboarding suggestion call failed, retrying once: {}", first.getMessage());
            try {
                return doCall(type, userMessage + "\nIMPORTANT: return ONLY valid JSON matching the schema.", user);
            } catch (RuntimeException second) {
                log.error("Onboarding suggestion retry failed", second);
                throw new BusinessException(ErrorKey.AI_UNAVAILABLE, "AI suggestions unavailable");
            }
        }
    }

    private <T> T doCall(Class<T> type, String userMessage, User user) {
        return chatClient.prompt()
                .system(s -> s.text(systemTemplate)
                        .param("language", user.getLanguageInUse() != null ? user.getLanguageInUse() : "en")
                        .param("iconCatalog", AiIconCatalog.promptCatalog())
                        .param("today", LocalDate.now().toString()))
                .user(userMessage)
                .call()
                .entity(type);
    }

    // ---- per-step user messages (the "onboarding so far" context rides along) ----

    private String categoriesMessage(List<String> names) {
        return """
                Create one category object for EVERY name in this list, keeping each name EXACTLY \
                as given (verbatim, same language): %s
                For each: a short motivating description (max 300 chars) and the best iconId from the catalog.
                """.formatted(names);
    }

    private String habitsTasksMessage(OnboardingContext context, String newRequest) {
        if (newRequest != null && !newRequest.isBlank()) {
            return contextBlock(context) + """
                    The user asked for something specific: "%s"
                    Return ONLY 1-3 NEW items matching that request (habits and/or tasks), nothing else.
                    Fields per habit: name, description, motivationalPhrase, iconId, categoryName \
                    (verbatim from the user's categories), importance 1-5, difficulty 1-5. \
                    Tasks: same minus motivationalPhrase.
                    """.formatted(newRequest);
        }
        return contextBlock(context) + """
                Suggest at least 8 habits and at least 4 tasks personalized to the user's categories.
                Habits are RECURRING actions that build streaks. Tasks are ONE-OFF or occasional to-dos. \
                Make the difference obvious through your choices.
                Fields per habit: name (max 100), description (max 300), motivationalPhrase (short), \
                iconId from the catalog, categoryName (MUST match one of the user's categories verbatim), \
                importance 1-5, difficulty 1-5. Tasks: same fields minus motivationalPhrase.
                """;
    }

    private String routineMessage(OnboardingContext context) {
        String feedback = context != null && context.feedback() != null ? context.feedback() : "";
        return contextBlock(context) + """
                Draft ONE daily routine using ONLY the user's habits and tasks listed above (refer to them \
                by their EXACT names — do not invent items). 3-5 sections covering the day, each with name, \
                iconId, startTime and endTime in HH:mm. Place each item in a fitting section with startTime \
                and endTime inside that section's window. Also pick scheduleDays: a subset of \
                Monday,Tuesday,Wednesday,Thursday,Friday,Saturday,Sunday that fits the routine.
                """ + (feedback.isBlank() ? "" : "\nAdapt the draft to this user feedback: \"" + feedback + "\"");
    }

    private String goalsMessage(OnboardingContext context, String newRequest) {
        if (newRequest != null && !newRequest.isBlank()) {
            return contextBlock(context) + """
                    The user described an aim: "%s"
                    Return ONLY 1-3 NEW goals matching it. Fields: name, description, iconId, categoryName \
                    (verbatim), targetValue (number), unit (short word), motivation, \
                    term one of SHORT_TERM|MEDIUM_TERM|LONG_TERM, durationDays (integer).
                    """.formatted(newRequest);
        }
        return contextBlock(context) + """
                Suggest 3-6 measurable goals matching the user's categories and habits. Fields: name, \
                description, iconId, categoryName (verbatim), targetValue (number), unit (short word, \
                e.g. "books", "km"), motivation (one sentence), term one of SHORT_TERM|MEDIUM_TERM|LONG_TERM, \
                durationDays (integer, days until the goal's end date).
                """;
    }

    /** Renders the stateless "small history" the frontend sends with every call. */
    private String contextBlock(OnboardingContext context) {
        if (context == null) return "Onboarding so far: (nothing yet)\n";
        StringBuilder sb = new StringBuilder("Onboarding so far:\n");
        if (context.categories() != null && !context.categories().isEmpty())
            sb.append("- User's categories: ").append(String.join(", ", context.categories())).append('\n');
        if (context.habits() != null && !context.habits().isEmpty())
            sb.append("- Accepted habits: ").append(refs(context.habits())).append('\n');
        if (context.tasks() != null && !context.tasks().isEmpty())
            sb.append("- Accepted tasks: ").append(refs(context.tasks())).append('\n');
        if (context.freeTexts() != null && !context.freeTexts().isEmpty())
            sb.append("- Earlier user requests: ").append(String.join(" | ", context.freeTexts())).append('\n');
        return sb.toString();
    }

    private String refs(List<OnboardingSuggestionRequest.ItemRef> items) {
        return String.join(", ", items.stream()
                .map(i -> i.name() + (i.categoryName() != null ? " (" + i.categoryName() + ")" : ""))
                .toList());
    }

    // ---- sanitization: never trust free-tier LLM output ----

    /** Guarantees exactly one suggestion per requested name, name kept verbatim. */
    private List<CategorySuggestion> verbatimCategories(List<String> names, CategoriesPayload payload) {
        List<CategorySuggestion> raw = payload.categories() != null ? payload.categories() : List.of();
        List<CategorySuggestion> result = new ArrayList<>();
        for (String name : names) {
            CategorySuggestion match = raw.stream()
                    .filter(c -> c.name() != null && c.name().trim().equalsIgnoreCase(name.trim()))
                    .findFirst()
                    // LLM renamed or dropped it — keep positional pairing as a best effort
                    .orElse(names.indexOf(name) < raw.size() ? raw.get(names.indexOf(name)) : null);
            result.add(new CategorySuggestion(name,
                    match != null && match.description() != null ? truncate(match.description(), 1024) : "",
                    AiIconCatalog.orDefault(match != null ? match.iconId() : null)));
        }
        return result;
    }

    private List<HabitSuggestion> sanitizeHabits(List<HabitSuggestion> habits) {
        if (habits == null) return List.of();
        return habits.stream().limit(MAX_ITEMS).map(h -> new HabitSuggestion(
                truncate(h.name(), 256), truncate(h.description(), 1000),
                truncate(h.motivationalPhrase(), 500), AiIconCatalog.orDefault(h.iconId()),
                h.categoryName(), clamp(h.importance()), clamp(h.difficulty()))).toList();
    }

    private List<TaskSuggestion> sanitizeTasks(List<TaskSuggestion> tasks) {
        if (tasks == null) return List.of();
        return tasks.stream().limit(MAX_ITEMS).map(t -> new TaskSuggestion(
                truncate(t.name(), 256), truncate(t.description(), 1000),
                AiIconCatalog.orDefault(t.iconId()), t.categoryName(),
                clamp(t.importance()), clamp(t.difficulty()))).toList();
    }

    /** Canonical wire format for schedule days — the frontend and the backend WeekDay enum
     *  both expect capitalized names; LLM output is normalized case-insensitively. */
    private static final List<String> WEEK_DAYS = List.of(
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday");

    private RoutineSuggestion sanitizeRoutine(RoutineSuggestion routine) {
        List<SectionSuggestion> sections = routine.sections() == null ? List.of()
                : routine.sections().stream().limit(8).map(s -> new SectionSuggestion(
                        truncate(s.name(), 256), AiIconCatalog.orDefault(s.iconId()),
                        s.startTime(), s.endTime(),
                        sanitizeItems(s.habits()),
                        sanitizeItems(s.tasks()))).toList();
        return new RoutineSuggestion(truncate(routine.name(), 256),
                AiIconCatalog.orDefault(routine.iconId()),
                normalizeDays(routine.scheduleDays()), sections);
    }

    private List<ItemPlacement> sanitizeItems(List<ItemPlacement> items) {
        if (items == null) return List.of();
        return items.stream().limit(MAX_ITEMS).map(i -> new ItemPlacement(
                truncate(i.name(), 256), i.startTime(), i.endTime())).toList();
    }

    /** Case-insensitive match against the canonical day names; unknown values dropped. */
    private List<String> normalizeDays(List<String> days) {
        if (days == null) return List.of();
        return days.stream()
                .map(d -> WEEK_DAYS.stream().filter(w -> w.equalsIgnoreCase(d)).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<GoalSuggestion> sanitizeGoals(List<GoalSuggestion> goals) {
        if (goals == null) return List.of();
        return goals.stream().limit(10).map(g -> new GoalSuggestion(
                truncate(g.name(), 256), truncate(g.description(), 1000),
                AiIconCatalog.orDefault(g.iconId()), g.categoryName(),
                g.targetValue() != null && g.targetValue() > 0 ? g.targetValue() : 1.0,
                g.unit() != null && !g.unit().isBlank() ? g.unit() : "times",
                truncate(g.motivation(), 256),
                List.of("SHORT_TERM", "MEDIUM_TERM", "LONG_TERM").contains(g.term()) ? g.term() : "SHORT_TERM",
                g.durationDays() != null && g.durationDays() > 0 ? g.durationDays() : 30)).toList();
    }

    private int clamp(Integer value) {
        return value == null ? 3 : Math.max(1, Math.min(5, value));
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
