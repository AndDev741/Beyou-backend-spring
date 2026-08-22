package beyou.beyouapp.backend.unit.aiAgent;

import beyou.beyouapp.backend.domain.aiAgent.AiIconCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiIconCatalogTest {

    @Test
    void validIconPassesThrough() {
        assertEquals("lucide:sun", AiIconCatalog.orDefault("lucide:sun"));
    }

    @Test
    void unknownIconFallsBackToDefault() {
        assertEquals(AiIconCatalog.DEFAULT_ICON, AiIconCatalog.orDefault("lucide:does-not-exist"));
        assertEquals(AiIconCatalog.DEFAULT_ICON, AiIconCatalog.orDefault("ri:md/MdWbSunny"));
        assertEquals(AiIconCatalog.DEFAULT_ICON, AiIconCatalog.orDefault(null));
        assertEquals(AiIconCatalog.DEFAULT_ICON, AiIconCatalog.orDefault(""));
    }

    @Test
    void promptCatalogListsEveryIconWithLabel() {
        String catalog = AiIconCatalog.promptCatalog();
        assertTrue(catalog.contains("lucide:sun"));
        assertTrue(catalog.contains("morning") || catalog.contains("daytime"));
        // Every entry has to reach the prompt, otherwise the model cannot pick it.
        assertEquals(AiIconCatalog.ICONS.size(), catalog.split(", (?=lucide:|emoji:)").length);
    }

    /**
     * The ids are consumed by the frontend registry, which has exactly two id spaces.
     * A malformed id does not fail loudly — it silently degrades to DEFAULT_ICON and the
     * user gets a star, so the shape is worth pinning here.
     */
    @Test
    void everyIdIsWellFormed() {
        Pattern shape = Pattern.compile("^(lucide:[a-z0-9]+(-[a-z0-9]+)*|emoji:[a-z0-9_+\\-]+)$");
        List<String> malformed = AiIconCatalog.ICONS.stream()
                .map(AiIconCatalog.IconOption::id)
                .filter(id -> !shape.matcher(id).matches())
                .collect(Collectors.toList());
        assertEquals(List.of(), malformed, "ids must be lucide:<kebab> or emoji:<short_name>");
    }

    @Test
    void hasNoDuplicateIds() {
        Set<String> seen = new HashSet<>();
        List<String> duplicated = AiIconCatalog.ICONS.stream()
                .map(AiIconCatalog.IconOption::id)
                .filter(id -> !seen.add(id))
                .collect(Collectors.toList());
        assertEquals(List.of(), duplicated);
    }

    @Test
    void everyEntryCarriesALabel() {
        assertTrue(AiIconCatalog.ICONS.stream().noneMatch(i -> i.label() == null || i.label().isBlank()));
    }

    /**
     * Faith was the gap that prompted the catalog to be widened: someone tracking prayer
     * or bible reading got a generic star, because nothing here spoke to it.
     */
    @Test
    void offersFaithIcons() {
        for (String id : List.of(
                "lucide:cross", "lucide:church", "lucide:book-marked",
                "emoji:latin_cross", "emoji:pray", "emoji:prayer_beads")) {
            assertTrue(AiIconCatalog.isValid(id), id + " should be offered to the model");
        }
    }

    @Test
    void offersBothLineIconsAndEmoji() {
        long lucide = AiIconCatalog.ICONS.stream().filter(i -> i.id().startsWith("lucide:")).count();
        long emoji = AiIconCatalog.ICONS.stream().filter(i -> i.id().startsWith("emoji:")).count();
        assertTrue(lucide > 100, "expected a broad line-icon set, got " + lucide);
        assertTrue(emoji > 40, "expected a broad emoji set, got " + emoji);
    }

    @Test
    void coversTheEverydayHabitAreas() {
        // One representative per area, so a whole slice of life cannot quietly go missing.
        for (String id : List.of(
                "lucide:dumbbell",      // fitness
                "lucide:glass-water",   // hydration
                "lucide:heart-pulse",   // health
                "lucide:book-open",     // study
                "lucide:briefcase",     // work
                "lucide:piggy-bank",    // money
                "lucide:house",         // home
                "lucide:paw-print",     // pets
                "lucide:leaf",          // nature
                "lucide:music",         // leisure
                "lucide:plane",         // travel
                "lucide:cross")) {      // faith
            assertTrue(AiIconCatalog.isValid(id), id + " missing from the catalog");
        }
        assertFalse(AiIconCatalog.isValid("lucide:definitely-not-real"));
    }
}
