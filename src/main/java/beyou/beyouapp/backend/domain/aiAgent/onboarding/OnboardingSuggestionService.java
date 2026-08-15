package beyou.beyouapp.backend.domain.aiAgent.onboarding;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Leading HH:mm of whatever the model wrote, with or without a leading zero. */
    private static final Pattern TIME_PATTERN = Pattern.compile("^(\\d{1,2}):(\\d{2})");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

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
                Draft ONE daily routine. Build it mostly out of the user's habits and tasks listed above, \
                referring to each by its EXACT name. 3-5 sections covering the day, each with name, \
                iconId, startTime and endTime in HH:mm. Place each item in a fitting section with startTime \
                and endTime inside that section's window. Every item's endTime must be LATER than its own \
                startTime, and both must fall between the section's startTime and endTime. Also pick \
                scheduleDays: a subset of \
                Monday,Tuesday,Wednesday,Thursday,Friday,Saturday,Sunday that fits the routine.

                If the day plainly needs something the user does not have yet, add it: put the full item in \
                newHabits (recurring) or newTasks (one-off), and place it in a section by that same name. \
                Fields per new habit: name, description, motivationalPhrase, iconId from the catalog, \
                categoryName matching one of the user's categories verbatim, importance 1-5, difficulty 1-5. \
                New tasks: the same minus motivationalPhrase. Keep it to at most 3 new items in total, and \
                only where a section would otherwise be empty or the day would have an obvious hole. Never \
                restate an item the user already has as a new one, and never place a name that is neither in \
                the lists above nor in newHabits/newTasks.
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
                : routine.sections().stream().limit(8).map(s -> {
                    LocalTime sectionStart = parseTime(s.startTime());
                    LocalTime sectionEnd = parseTime(s.endTime());
                    return new SectionSuggestion(
                            truncate(s.name(), 256), AiIconCatalog.orDefault(s.iconId()),
                            formatTime(sectionStart), formatTime(sectionEnd),
                            sanitizeItems(s.habits(), sectionStart, sectionEnd),
                            sanitizeItems(s.tasks(), sectionStart, sectionEnd));
                }).toList();
        // Through the same sanitizers the HABITS_TASKS step uses, then capped: the
        // prompt asks for at most three, and a model that ignores that would otherwise
        // have the wizard create a pile of entities nobody asked for.
        List<HabitSuggestion> newHabits = sanitizeHabits(routine.newHabits()).stream()
                .limit(MAX_NEW_ROUTINE_ITEMS).toList();
        List<TaskSuggestion> newTasks = sanitizeTasks(routine.newTasks()).stream()
                .limit(Math.max(0, MAX_NEW_ROUTINE_ITEMS - newHabits.size())).toList();

        return new RoutineSuggestion(truncate(routine.name(), 256),
                AiIconCatalog.orDefault(routine.iconId()),
                normalizeDays(routine.scheduleDays()), sections, newHabits, newTasks);
    }

    /**
     * How many items the routine step may invent, habits and tasks together.
     *
     * The step exists to arrange a day, not to keep suggesting things: someone who has
     * just accepted eight habits and four tasks does not want the next screen adding
     * six more. Three is enough to close an obvious hole and small enough that the
     * user can see at a glance what was added.
     */
    private static final int MAX_NEW_ROUTINE_ITEMS = 3;

    /**
     * Item placements POST /routine will actually accept.
     *
     * The model writes HH:mm by hand, and the free tiers get it wrong in the exact
     * ways the routine endpoint refuses: an item ending before it starts, or an item
     * sitting outside the section it was placed in. That refusal reached production
     * as a dead end in the onboarding wizard, where the user has no way to edit a
     * suggestion before it is created ("End time must be after start time for habit
     * in routine section: Foco Profissional"). A time nudged into its section is a
     * smaller lie than a wizard that cannot finish.
     *
     * Overnight sections (end before start, say 22:00 to 06:00) are left alone. The
     * endpoint accepts them, and clamping across midnight would throw items to the
     * wrong side of it.
     */
    private List<ItemPlacement> sanitizeItems(List<ItemPlacement> items, LocalTime sectionStart, LocalTime sectionEnd) {
        if (items == null) return List.of();
        boolean bothBounds = sectionStart != null && sectionEnd != null;
        boolean overnight = bothBounds && sectionEnd.isBefore(sectionStart);
        boolean bounded = bothBounds && sectionEnd.isAfter(sectionStart);
        return items.stream().limit(MAX_ITEMS).map(i -> {
            LocalTime start = parseTime(i.startTime());
            LocalTime end = parseTime(i.endTime());
            if (bounded) {
                if (start != null) {
                    start = clamp(start, sectionStart, sectionEnd);
                }
                if (end != null) {
                    end = clamp(end, start != null ? start : sectionStart, sectionEnd);
                }
            } else if (!overnight && start != null && end != null && end.isBefore(start)) {
                // No usable section window, so the only rule left is the endpoint's own:
                // an item may not end before it starts.
                end = start;
            }
            return new ItemPlacement(truncate(i.name(), 256), formatTime(start), formatTime(end));
        }).toList();
    }

    private LocalTime clamp(LocalTime time, LocalTime min, LocalTime max) {
        if (time.isBefore(min)) return min;
        if (time.isAfter(max)) return max;
        return time;
    }

    /**
     * HH:mm, forgiving a missing leading zero and trailing seconds, and reading the
     * 24:00 that models write for midnight as the last minute of the day. Anything
     * else becomes null, which the routine endpoint treats as "no time given".
     */
    private LocalTime parseTime(String raw) {
        if (raw == null) return null;
        Matcher matcher = TIME_PATTERN.matcher(raw.trim());
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour == 24 && minute == 0) {
            return LocalTime.of(23, 59);
        }
        if (hour > 23 || minute > 59) {
            return null;
        }
        return LocalTime.of(hour, minute);
    }

    private String formatTime(LocalTime time) {
        return time == null ? null : time.format(HH_MM);
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
