package beyou.beyouapp.backend.domain.common;

public enum ExperienceLevel {
    BEGINNER(0, 0),
    INTERMEDIARY(5, 750),
    ADVANCED(8, 1800);

    private final int level;
    private final int xp;

    ExperienceLevel(int level, int xp) {
        this.level = level;
        this.xp = xp;
    }

    public int getLevel() { return level; }
    public int getXp() { return xp; }
}
