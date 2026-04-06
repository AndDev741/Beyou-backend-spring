package beyou.beyouapp.backend.unit.common;

import beyou.beyouapp.backend.domain.common.ExperienceLevel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExperienceLevelTest {
    @Test
    void beginnerShouldHaveLevel0AndXp0() {
        assertEquals(0, ExperienceLevel.BEGINNER.getLevel());
        assertEquals(0, ExperienceLevel.BEGINNER.getXp());
    }
    @Test
    void intermediaryShouldHaveLevel5AndXp750() {
        assertEquals(5, ExperienceLevel.INTERMEDIARY.getLevel());
        assertEquals(750, ExperienceLevel.INTERMEDIARY.getXp());
    }
    @Test
    void advancedShouldHaveLevel8AndXp1800() {
        assertEquals(8, ExperienceLevel.ADVANCED.getLevel());
        assertEquals(1800, ExperienceLevel.ADVANCED.getXp());
    }
}
