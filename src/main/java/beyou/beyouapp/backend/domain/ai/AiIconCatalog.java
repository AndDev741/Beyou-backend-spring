package beyou.beyouapp.backend.domain.ai;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Curated allowlist of icon ids the AI may use. Ids must exist in the
 * frontend icon registry (react-icons md set, canonical form "ri:md/<Name>" —
 * see Beyou-Frontend/src/components/icons/iconRegistry.ts). Unknown ids
 * degrade to DEFAULT_ICON instead of failing the draft.
 */
public final class AiIconCatalog {

    public static final String DEFAULT_ICON = "ri:md/MdStar";

    public record IconOption(String id, String label) {}

    public static final List<IconOption> ICONS = List.of(
            new IconOption("ri:md/MdWbSunny", "sun, morning"),
            new IconOption("ri:md/MdNightlight", "moon, evening"),
            new IconOption("ri:md/MdBedtime", "sleep, bedtime"),
            new IconOption("ri:md/MdAlarm", "alarm, wake up"),
            new IconOption("ri:md/MdFitnessCenter", "gym, workout"),
            new IconOption("ri:md/MdDirectionsRun", "running, cardio"),
            new IconOption("ri:md/MdDirectionsBike", "cycling"),
            new IconOption("ri:md/MdPool", "swimming"),
            new IconOption("ri:md/MdSelfImprovement", "meditation, mindfulness"),
            new IconOption("ri:md/MdSpa", "wellness, relax"),
            new IconOption("ri:md/MdLocalDrink", "water, hydration"),
            new IconOption("ri:md/MdRestaurant", "meal, food"),
            new IconOption("ri:md/MdBreakfastDining", "breakfast"),
            new IconOption("ri:md/MdLunchDining", "lunch"),
            new IconOption("ri:md/MdDinnerDining", "dinner"),
            new IconOption("ri:md/MdLocalCafe", "coffee, break"),
            new IconOption("ri:md/MdMedication", "medication, vitamins"),
            new IconOption("ri:md/MdHealthAndSafety", "health"),
            new IconOption("ri:md/MdMenuBook", "reading, book"),
            new IconOption("ri:md/MdSchool", "study, learning"),
            new IconOption("ri:md/MdCode", "coding, programming"),
            new IconOption("ri:md/MdWork", "work, job"),
            new IconOption("ri:md/MdLaptop", "computer, deep work"),
            new IconOption("ri:md/MdEmail", "email, inbox"),
            new IconOption("ri:md/MdCall", "call, phone"),
            new IconOption("ri:md/MdChecklist", "planning, todo"),
            new IconOption("ri:md/MdCleaningServices", "cleaning, chores"),
            new IconOption("ri:md/MdShoppingCart", "shopping, groceries"),
            new IconOption("ri:md/MdAttachMoney", "money, finances"),
            new IconOption("ri:md/MdSavings", "savings, budget"),
            new IconOption("ri:md/MdBrush", "art, creativity"),
            new IconOption("ri:md/MdMusicNote", "music, instrument"),
            new IconOption("ri:md/MdSportsSoccer", "sports, soccer"),
            new IconOption("ri:md/MdSportsEsports", "games, leisure"),
            new IconOption("ri:md/MdPets", "pets"),
            new IconOption("ri:md/MdFamilyRestroom", "family"),
            new IconOption("ri:md/MdVolunteerActivism", "volunteering, kindness"),
            new IconOption("ri:md/MdPark", "outdoors, nature"),
            new IconOption("ri:md/MdFavorite", "heart, self care"),
            new IconOption("ri:md/MdStar", "star, generic"));

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
