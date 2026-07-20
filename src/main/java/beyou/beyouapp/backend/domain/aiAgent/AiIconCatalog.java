package beyou.beyouapp.backend.domain.aiAgent;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Curated allowlist of icon ids the AI may use. Ids must exist in the
 * frontend icon registry (Beyou-Frontend/packages/icons/src/registry.ts),
 * which has two id spaces:
 *   - lucide icons — "lucide:<kebab-name>" (packages/icons/src/data/lucideNames.json)
 *   - emoji        — "emoji:<short_name>"  (packages/icons/src/data/emojiCharMap.json)
 * Unknown ids degrade to DEFAULT_ICON instead of failing the draft.
 */
public final class AiIconCatalog {

    public static final String DEFAULT_ICON = "lucide:star";

    public record IconOption(String id, String label) {}

    public static final List<IconOption> ICONS = List.of(
            new IconOption("lucide:sun", "sun, morning"),
            new IconOption("lucide:sunrise", "sunrise, wake up early"),
            new IconOption("lucide:moon", "moon, evening"),
            new IconOption("lucide:bed", "sleep, bedtime"),
            new IconOption("lucide:alarm-clock", "alarm, wake up"),
            new IconOption("lucide:dumbbell", "gym, workout"),
            new IconOption("lucide:footprints", "running, cardio, walk"),
            new IconOption("lucide:bike", "cycling"),
            new IconOption("lucide:waves", "swimming"),
            new IconOption("lucide:brain", "meditation, mindfulness"),
            new IconOption("lucide:leaf", "wellness, relax, nature"),
            new IconOption("lucide:glass-water", "water, hydration"),
            new IconOption("lucide:utensils", "meal, food"),
            new IconOption("lucide:egg", "breakfast"),
            new IconOption("lucide:sandwich", "lunch"),
            new IconOption("lucide:utensils-crossed", "dinner"),
            new IconOption("lucide:coffee", "coffee, break"),
            new IconOption("lucide:pill", "medication, vitamins"),
            new IconOption("lucide:heart-pulse", "health"),
            new IconOption("lucide:book-open", "reading, book"),
            new IconOption("lucide:graduation-cap", "study, learning"),
            new IconOption("lucide:code", "coding, programming"),
            new IconOption("lucide:briefcase", "work, job"),
            new IconOption("lucide:laptop", "computer, deep work"),
            new IconOption("lucide:mail", "email, inbox"),
            new IconOption("lucide:phone", "call, phone"),
            new IconOption("lucide:list-checks", "planning, todo"),
            new IconOption("lucide:spray-can", "cleaning, chores"),
            new IconOption("lucide:shopping-cart", "shopping, groceries"),
            new IconOption("lucide:dollar-sign", "money, finances"),
            new IconOption("lucide:piggy-bank", "savings, budget"),
            new IconOption("lucide:palette", "art, creativity"),
            new IconOption("lucide:music", "music, instrument"),
            new IconOption("lucide:trophy", "sports, competition"),
            new IconOption("lucide:gamepad-2", "games, leisure"),
            new IconOption("lucide:paw-print", "pets"),
            new IconOption("lucide:users", "family"),
            new IconOption("lucide:heart-handshake", "volunteering, kindness"),
            new IconOption("lucide:trees", "outdoors, nature"),
            new IconOption("lucide:heart", "heart, self care"),
            new IconOption("lucide:star", "star, generic"),
            // Emojis — expressive/motivational alternatives to the line icons above.
            new IconOption("emoji:fire", "fire, streak, motivation"),
            new IconOption("emoji:muscle", "strength, workout"),
            new IconOption("emoji:sparkles", "sparkles, fresh start"),
            new IconOption("emoji:tada", "celebration, milestone"),
            new IconOption("emoji:rocket", "rocket, productivity, launch"),
            new IconOption("emoji:seedling", "growth, new habit"),
            new IconOption("emoji:pray", "gratitude, mindfulness"),
            new IconOption("emoji:green_salad", "healthy eating"),
            new IconOption("emoji:apple", "fruit, nutrition"),
            new IconOption("emoji:moneybag", "money, finances"),
            new IconOption("emoji:chart_with_upwards_trend", "progress, tracking"),
            new IconOption("emoji:art", "art, creativity"),
            new IconOption("emoji:musical_note", "music"),
            new IconOption("emoji:soccer", "sports"),
            new IconOption("emoji:video_game", "games, leisure"));

    private static final Set<String> IDS = ICONS.stream()
            .map(IconOption::id)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isValid(String iconId) {
        return iconId != null && IDS.contains(iconId);
    }

    public static String orDefault(String iconId) {
        return isValid(iconId) ? iconId : DEFAULT_ICON;
    }

    /** One-line catalog injected into the system prompt. */
    public static String promptCatalog() {
        return ICONS.stream()
                .map(icon -> icon.id() + " (" + icon.label() + ")")
                .collect(Collectors.joining(", "));
    }

    private AiIconCatalog() {}
}
