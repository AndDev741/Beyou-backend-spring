package beyou.beyouapp.backend.unit.common;

import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import beyou.beyouapp.backend.domain.common.XpProgress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class XpProgressTest {

    private static final Map<Integer, Double> XP_TABLE = Map.of(
            1, 0.0,
            2, 100.0,
            3, 300.0,
            4, 600.0,
            5, 1000.0
    );

    private XpProgress xpProgress;
    private Function<Integer, XpByLevel> levelProvider;

    @BeforeEach
    void setUp() {
        xpProgress = new XpProgress();
        levelProvider = level -> new XpByLevel(level, XP_TABLE.getOrDefault(level, XP_TABLE.get(5)));
    }

    private void initializeProgress(double xp, int level) {
        xpProgress.setXp(xp);
        xpProgress.setLevel(level);
        xpProgress.setActualLevelXp(XP_TABLE.getOrDefault(level, 0.0));
        xpProgress.setNextLevelXp(XP_TABLE.getOrDefault(level + 1, XP_TABLE.get(5)));
    }

    @Test
    void addXpShouldLevelUpOnceWhenCrossingNextThreshold() {
        initializeProgress(90.0, 1);

        xpProgress.addXp(20.0, levelProvider);

        assertEquals(110.0, xpProgress.getXp());
        assertEquals(2, xpProgress.getLevel());
        assertEquals(100.0, xpProgress.getActualLevelXp());
        assertEquals(300.0, xpProgress.getNextLevelXp());
    }

    @Test
    void addXpShouldHandleMultipleLevelUps() {
        initializeProgress(90.0, 1);

        xpProgress.addXp(400.0, levelProvider);

        assertEquals(490.0, xpProgress.getXp());
        assertEquals(3, xpProgress.getLevel());
        assertEquals(300.0, xpProgress.getActualLevelXp());
        assertEquals(600.0, xpProgress.getNextLevelXp());
    }

    @Test
    void removeXpShouldLevelDownAndRecalculateBoundaries() {
        initializeProgress(350.0, 3);

        xpProgress.removeXp(100.0, levelProvider);

        assertEquals(250.0, xpProgress.getXp());
        assertEquals(2, xpProgress.getLevel());
        assertEquals(100.0, xpProgress.getActualLevelXp());
        assertEquals(300.0, xpProgress.getNextLevelXp());
    }

    @Test
    void removeXpShouldStopLevelingDownWhenXpIsDepleted() {
        initializeProgress(80.0, 1);

        xpProgress.removeXp(100.0, levelProvider);

        assertEquals(-20.0, xpProgress.getXp());
        assertEquals(1, xpProgress.getLevel());
        assertEquals(0.0, xpProgress.getActualLevelXp());
        assertEquals(100.0, xpProgress.getNextLevelXp());
    }
}
