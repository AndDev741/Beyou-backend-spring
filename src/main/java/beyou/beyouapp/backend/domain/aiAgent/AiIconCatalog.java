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
 *
 * The list is grouped by what a habit is ABOUT rather than by id space, and each group
 * offers both line icons and emoji so the model can pick a register that suits the
 * habit. It deliberately covers faith: a user tracking prayer or bible reading used to
 * get a generic star, because the catalog had nothing to offer them.
 *
 * Every entry is rendered into the system prompt by {@link #promptCatalog()}, so the
 * list is not free — it costs roughly 2k tokens on every AI call. Add to it when a
 * whole area of life is missing, not to cover one more synonym of something listed.
 */
public final class AiIconCatalog {

    public static final String DEFAULT_ICON = "lucide:star";

    public record IconOption(String id, String label) {}

    public static final List<IconOption> ICONS = List.of(
            // Time of day and routine anchors
            new IconOption("lucide:sun", "sun, daytime"),
            new IconOption("lucide:sunrise", "sunrise, wake up early"),
            new IconOption("lucide:sunset", "sunset, end of day"),
            new IconOption("lucide:moon", "moon, evening"),
            new IconOption("lucide:moon-star", "night, bedtime"),
            new IconOption("lucide:bed", "sleep, bedtime"),
            new IconOption("lucide:alarm-clock", "alarm, wake up"),
            new IconOption("lucide:clock", "time, schedule"),
            new IconOption("lucide:hourglass", "duration, time box"),
            new IconOption("lucide:calendar", "calendar, planning"),
            new IconOption("lucide:calendar-check", "appointment, scheduled"),
            new IconOption("lucide:repeat", "daily, recurring habit"),
            new IconOption("lucide:timer", "timer, interval"),
            new IconOption("emoji:sleeping", "sleep, rest"),
            new IconOption("emoji:alarm_clock", "alarm, morning"),
            // Fitness and movement
            new IconOption("lucide:dumbbell", "gym, weights, strength"),
            new IconOption("lucide:biceps-flexed", "muscle, strength"),
            new IconOption("lucide:footprints", "running, walking, steps"),
            new IconOption("lucide:bike", "cycling"),
            new IconOption("lucide:waves", "swimming"),
            new IconOption("lucide:mountain", "hiking, climbing"),
            new IconOption("lucide:person-standing", "posture, stretching"),
            new IconOption("lucide:weight", "body weight, scale"),
            new IconOption("lucide:trophy", "achievement, competition"),
            new IconOption("lucide:medal", "award, milestone"),
            new IconOption("lucide:target", "goal, focus"),
            new IconOption("lucide:volleyball", "team sport"),
            new IconOption("emoji:muscle", "strength, workout"),
            new IconOption("emoji:runner", "running, cardio"),
            new IconOption("emoji:soccer", "football, sport"),
            new IconOption("emoji:basketball", "basketball"),
            new IconOption("emoji:tennis", "tennis, racket sport"),
            new IconOption("emoji:swimmer", "swimming"),
            new IconOption("emoji:person_in_lotus_position", "yoga, meditation, stretching"),
            new IconOption("emoji:fire", "streak, motivation"),
            // Health, mind and hygiene
            new IconOption("lucide:heart-pulse", "health, vitals"),
            new IconOption("lucide:stethoscope", "doctor, appointment"),
            new IconOption("lucide:pill", "medication, vitamins"),
            new IconOption("lucide:syringe", "vaccine, injection"),
            new IconOption("lucide:thermometer", "fever, temperature"),
            new IconOption("lucide:bandage", "injury, first aid"),
            new IconOption("lucide:brain", "mental health, focus, therapy"),
            new IconOption("lucide:eye", "vision, eye care"),
            new IconOption("lucide:hospital", "hospital, clinic"),
            new IconOption("lucide:accessibility", "accessibility, mobility"),
            new IconOption("lucide:shower-head", "shower, hygiene"),
            new IconOption("lucide:bath", "bath, relaxing"),
            new IconOption("emoji:tooth", "brushing teeth, dentist"),
            new IconOption("emoji:toothbrush", "brushing teeth"),
            new IconOption("emoji:soap", "washing, hygiene"),
            new IconOption("emoji:lotion_bottle", "skincare, routine"),
            new IconOption("emoji:face_with_thermometer", "sick, recovery"),
            // Food, drink and hydration
            new IconOption("lucide:utensils", "meal, eating"),
            new IconOption("lucide:glass-water", "water, hydration"),
            new IconOption("lucide:coffee", "coffee, tea, break"),
            new IconOption("lucide:apple", "fruit, healthy snack"),
            new IconOption("lucide:carrot", "vegetables"),
            new IconOption("lucide:salad", "salad, healthy eating"),
            new IconOption("lucide:egg", "breakfast, protein"),
            new IconOption("lucide:sandwich", "lunch"),
            new IconOption("lucide:soup", "dinner, warm meal"),
            new IconOption("lucide:cooking-pot", "cooking, meal prep"),
            new IconOption("lucide:chef-hat", "cooking, recipe"),
            new IconOption("lucide:beef", "meat, protein"),
            new IconOption("lucide:fish", "fish, seafood"),
            new IconOption("lucide:wine", "wine, alcohol"),
            new IconOption("lucide:beer", "beer, drinks out"),
            new IconOption("lucide:cake", "dessert, celebration"),
            new IconOption("emoji:green_salad", "healthy eating"),
            new IconOption("emoji:apple", "fruit, nutrition"),
            new IconOption("emoji:potable_water", "drinking water"),
            new IconOption("emoji:coffee", "coffee, tea"),
            new IconOption("emoji:bread", "bread, breakfast"),
            // Faith and spirituality
            new IconOption("lucide:cross", "cross, christian faith"),
            new IconOption("lucide:church", "church, mass, worship"),
            new IconOption("lucide:book-marked", "bible, scripture, devotional"),
            new IconOption("emoji:latin_cross", "cross, christian faith"),
            new IconOption("emoji:orthodox_cross", "orthodox cross"),
            new IconOption("emoji:church", "church, mass"),
            new IconOption("emoji:pray", "prayer, gratitude"),
            new IconOption("emoji:prayer_beads", "rosary, prayer beads"),
            new IconOption("emoji:place_of_worship", "worship, temple"),
            new IconOption("emoji:dove_of_peace", "dove, peace, holy spirit"),
            new IconOption("emoji:candle", "candle, vigil"),
            new IconOption("emoji:closed_book", "bible, scripture"),
            new IconOption("emoji:star_of_david", "judaism"),
            new IconOption("emoji:star_and_crescent", "islam"),
            new IconOption("emoji:om_symbol", "hinduism, meditation"),
            new IconOption("emoji:wheel_of_dharma", "buddhism"),
            // Study and learning
            new IconOption("lucide:book-open", "reading, study"),
            new IconOption("lucide:graduation-cap", "course, degree, school"),
            new IconOption("lucide:school", "school, class"),
            new IconOption("lucide:library", "library"),
            new IconOption("lucide:notebook-pen", "journal, diary, notes"),
            new IconOption("lucide:pencil", "writing, homework"),
            new IconOption("lucide:highlighter", "study notes, revision"),
            new IconOption("lucide:languages", "language learning"),
            new IconOption("lucide:calculator", "math"),
            new IconOption("lucide:atom", "science, physics"),
            new IconOption("lucide:flask-conical", "chemistry, experiment"),
            new IconOption("lucide:lightbulb", "idea, insight"),
            new IconOption("lucide:presentation", "presentation, lecture"),
            new IconOption("lucide:newspaper", "news, articles"),
            new IconOption("emoji:books", "books, studying"),
            new IconOption("emoji:memo", "notes, journaling"),
            new IconOption("emoji:writing_hand", "writing by hand"),
            new IconOption("emoji:nerd_face", "studying, learning"),
            // Work and productivity
            new IconOption("lucide:briefcase", "work, job, career"),
            new IconOption("lucide:laptop", "computer, deep work"),
            new IconOption("lucide:monitor", "desk work, screen"),
            new IconOption("lucide:building-2", "office, company"),
            new IconOption("lucide:clipboard-list", "to-do, planning"),
            new IconOption("lucide:list-checks", "checklist, tasks"),
            new IconOption("lucide:mail", "email, inbox"),
            new IconOption("lucide:phone", "phone call"),
            new IconOption("lucide:handshake", "meeting, agreement"),
            new IconOption("lucide:users", "team, group"),
            new IconOption("lucide:megaphone", "marketing, outreach"),
            new IconOption("lucide:printer", "printing, paperwork"),
            new IconOption("lucide:code", "coding, programming"),
            new IconOption("lucide:terminal", "terminal, scripting"),
            new IconOption("emoji:briefcase", "work, business"),
            new IconOption("emoji:rocket", "launch, productivity"),
            new IconOption("emoji:computer", "computer work"),
            new IconOption("emoji:bar_chart", "reporting, metrics"),
            // Money and finances
            new IconOption("lucide:dollar-sign", "money, salary"),
            new IconOption("lucide:coins", "coins, savings"),
            new IconOption("lucide:wallet", "budget, spending"),
            new IconOption("lucide:piggy-bank", "savings, investing"),
            new IconOption("lucide:banknote", "cash, payment"),
            new IconOption("lucide:credit-card", "card, bill"),
            new IconOption("lucide:receipt", "expense, invoice"),
            new IconOption("lucide:landmark", "bank, institution"),
            new IconOption("lucide:trending-up", "growth, profit"),
            new IconOption("lucide:chart-line", "tracking, analytics"),
            new IconOption("lucide:chart-pie", "distribution, allocation"),
            new IconOption("lucide:shopping-cart", "groceries, shopping"),
            new IconOption("lucide:store", "store, small business"),
            new IconOption("lucide:hand-coins", "donation, tithe, giving"),
            new IconOption("emoji:moneybag", "money, savings"),
            new IconOption("emoji:chart_with_upwards_trend", "progress, growth"),
            // Home and chores
            new IconOption("lucide:house", "home, household"),
            new IconOption("lucide:sofa", "living room, rest"),
            new IconOption("lucide:washing-machine", "laundry"),
            new IconOption("lucide:trash-2", "taking out rubbish"),
            new IconOption("lucide:brush", "cleaning, tidying"),
            new IconOption("lucide:hammer", "repairs, DIY"),
            new IconOption("lucide:wrench", "maintenance, fixing"),
            new IconOption("lucide:paint-roller", "painting, decorating"),
            new IconOption("lucide:lamp", "lighting, room"),
            new IconOption("lucide:key", "keys, leaving home"),
            new IconOption("lucide:refrigerator", "kitchen, groceries"),
            new IconOption("lucide:warehouse", "garage, storage"),
            new IconOption("emoji:broom", "sweeping, cleaning"),
            new IconOption("emoji:house", "home"),
            // Family, friends and pets
            new IconOption("lucide:users-round", "family, friends"),
            new IconOption("lucide:heart", "love, self care"),
            new IconOption("lucide:heart-handshake", "kindness, volunteering"),
            new IconOption("lucide:baby", "baby, parenting"),
            new IconOption("lucide:paw-print", "pet, walking the dog"),
            new IconOption("lucide:message-circle-heart", "calling family, checking in"),
            new IconOption("lucide:party-popper", "party, celebration"),
            new IconOption("lucide:gift", "gift, birthday"),
            new IconOption("emoji:family", "family"),
            new IconOption("emoji:dog", "dog, pet"),
            new IconOption("emoji:cat", "cat, pet"),
            new IconOption("emoji:heart", "love, affection"),
            // Nature and outdoors
            new IconOption("lucide:leaf", "nature, wellness"),
            new IconOption("lucide:sprout", "growth, new habit"),
            new IconOption("lucide:trees", "forest, outdoors"),
            new IconOption("lucide:flower", "flowers, garden"),
            new IconOption("lucide:droplet", "water, hydration"),
            new IconOption("lucide:cloud-rain", "rain, weather"),
            new IconOption("lucide:snowflake", "cold, winter"),
            new IconOption("lucide:wind", "wind, fresh air"),
            new IconOption("lucide:earth", "world, environment"),
            new IconOption("lucide:bird", "birds, nature"),
            new IconOption("lucide:tent-tree", "camping, outdoors"),
            new IconOption("emoji:seedling", "growth, new start"),
            new IconOption("emoji:deciduous_tree", "tree, nature"),
            new IconOption("emoji:ocean", "sea, beach"),
            new IconOption("emoji:sunny", "sunny, good weather"),
            // Leisure, creativity and travel
            new IconOption("lucide:music", "music, listening"),
            new IconOption("lucide:headphones", "podcast, audio"),
            new IconOption("lucide:guitar", "guitar practice"),
            new IconOption("lucide:piano", "piano practice"),
            new IconOption("lucide:mic-vocal", "singing"),
            new IconOption("lucide:film", "movies, cinema"),
            new IconOption("lucide:camera", "photography"),
            new IconOption("lucide:palette", "art, painting"),
            new IconOption("lucide:paintbrush", "drawing, painting"),
            new IconOption("lucide:gamepad-2", "video games"),
            new IconOption("lucide:dices", "board games"),
            new IconOption("lucide:puzzle", "puzzles, logic"),
            new IconOption("lucide:ticket", "event, show"),
            new IconOption("lucide:plane", "flight, travel"),
            new IconOption("lucide:car", "driving, commute"),
            new IconOption("lucide:bus", "public transport"),
            new IconOption("lucide:train-front", "train, metro"),
            new IconOption("lucide:map", "route, navigation"),
            new IconOption("lucide:luggage", "packing, trip"),
            new IconOption("lucide:tree-palm", "holiday, beach"),
            new IconOption("emoji:musical_note", "music"),
            new IconOption("emoji:art", "art, creativity"),
            new IconOption("emoji:video_game", "games, leisure"),
            new IconOption("emoji:camera", "photos"),
            new IconOption("emoji:clapper", "films"),
            new IconOption("emoji:airplane", "travel, flight"),
            // Generic and celebratory
            new IconOption("lucide:star", "star, generic"),
            new IconOption("lucide:flame", "streak, energy"),
            new IconOption("lucide:sparkles", "special, fresh start"),
            new IconOption("lucide:bell", "reminder"),
            new IconOption("lucide:circle-check-big", "done, completed"),
            new IconOption("emoji:sparkles", "sparkles, fresh start"),
            new IconOption("emoji:tada", "celebration, milestone"),
            new IconOption("emoji:100", "full effort, perfect score"),
            new IconOption("emoji:+1", "good, approved"));

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
