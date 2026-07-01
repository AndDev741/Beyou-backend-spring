package beyou.beyouapp.backend.unit.ai;

import beyou.beyouapp.backend.domain.ai.AiIconCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(catalog.contains("morning"));
    }
}
