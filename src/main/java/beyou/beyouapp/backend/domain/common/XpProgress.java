package beyou.beyouapp.backend.domain.common;

import java.util.function.Function;

import beyou.beyouapp.backend.domain.category.xpbylevel.XpByLevel;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class XpProgress {
    private double xp = 0D;
    private int level = 0;
    private double actualLevelXp = 0D;
    private double nextLevelXp = 0D;

    public void addXp(double amount, Function<Integer, XpByLevel> levelProvider){
        xp += amount;

        // Stop at the top of the curve: when there is no level+1 row, recalculate
        // pins nextLevelXp to the current threshold, so this loop would spin forever.
        while (xp >= nextLevelXp && levelProvider.apply(level + 1) != null) {
            level++;
            recalculate(levelProvider);
        }
    }

    public void removeXp(double amount,  Function<Integer, XpByLevel> levelProvider){
        xp -= amount;
        xp = Math.max(0, xp);

        while (xp < actualLevelXp && xp >= 0) {
            level--;
            recalculate(levelProvider);
        }
    }

    private void recalculate(Function<Integer, XpByLevel> levelProvider){
        XpByLevel actual = levelProvider.apply(level);
        XpByLevel next = levelProvider.apply(level + 1);

        actualLevelXp = actual.getXp();
        // At the top level there is no next row; pin the ceiling to the current
        // threshold instead of NPEing when a maxed entity gains more XP.
        nextLevelXp = (next != null) ? next.getXp() : actualLevelXp;
    }
}
