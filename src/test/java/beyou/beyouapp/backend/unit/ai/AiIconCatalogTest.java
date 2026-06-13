package beyou.beyouapp.backend.unit.ai;

import beyou.beyouapp.backend.domain.ai.AiIconCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiIconCatalogTest {

    @Test
    void validIconPassesThrough() {
        assertEquals("ri:md/MdWbSunny", AiIconCatalog.orDefault("ri:md/MdWbSunny"));
    }

    @Test
    void unknownIconFallsBackToDefault() {
        assertEquals(AiIconCatalog.DEFAULT_ICON, AiIconCatalog.orDefault("ri:md/MdDoesNotExist"));
        assertEquals(AiIconCatalog.DEFAULT_ICON, AiIconCatalog.orDefault(null));
        assertEquals(AiIconCatalog.DEFAULT_ICON, AiIconCatalog.orDefault(""));
    }

    @Test
    void promptCatalogListsEveryIconWithLabel() {
        String catalog = AiIconCatalog.promptCatalog();
        assertTrue(catalog.contains("ri:md/MdWbSunny"));
        assertTrue(catalog.contains("morning"));
    }
}
