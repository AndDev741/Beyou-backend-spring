package beyou.beyouapp.backend.domain.common;

import java.util.function.Function;

import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

@Embeddable
@Getter
@Setter
public class XpProgress {
    private double xp;
    private int level;
    private double actualLevelXp;
    private double nextLevelXp;

    public void addXp(double amount, Function<Integer, XpByLevel> levelProvider){
        xp += amount;

        while (xp >= nextLevelXp) {
            level++;
            recalculate(levelProvider);
        }
    }

    public void removeXp(double amount,  Function<Integer, XpByLevel> levelProvider){
        xp -= amount;

        while (xp < actualLevelXp && xp > 0) {
            level--;
            recalculate(levelProvider);
        }
    }

    private void recalculate(Function<Integer, XpByLevel> levelProvider){
        XpByLevel actual = levelProvider.apply(level);
        XpByLevel next = levelProvider.apply(level + 1);

        actualLevelXp = actual.getXp();
        nextLevelXp = next.getXp();
    }
}
