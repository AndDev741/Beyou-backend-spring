package beyou.beyouapp.backend.integration.routine.snapshot;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.domain.routine.snapshot.XpDecayStrategy;

import static org.junit.jupiter.api.Assertions.*;

class XpDecayStrategyTest {
    @Test
    void shouldHaveThreeValues() {
        assertEquals(3, XpDecayStrategy.values().length);
        assertNotNull(XpDecayStrategy.valueOf("GRADUAL"));
        assertNotNull(XpDecayStrategy.valueOf("FLAT"));
        assertNotNull(XpDecayStrategy.valueOf("TIME_WINDOW"));
    }
}
