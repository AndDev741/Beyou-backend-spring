package beyou.beyouapp.backend.domain.xpday;

/**
 * Which kind of entity an {@link EntityXpDay} row describes.
 *
 * <p>Exactly the four that embed {@code XpProgress}. It is deliberately NOT
 * {@code CheckDayOwnerType}: that one has TASK, because tasks are checked, and no
 * CATEGORY, because categories are not. This one is the mirror image — a task carries
 * no XP of its own (checking one feeds the user, the routine and the categories),
 * while a category accumulates the XP of everything inside it.
 *
 * <p>Persisted as a string and mirrored by the {@code entity_xp_day_owner_type_check}
 * constraint in {@code V19__entity_xp_day.sql} — adding a value here without adding it
 * there makes every insert of the new kind fail.
 */
public enum XpDayOwnerType {
    USER,
    CATEGORY,
    HABIT,
    ROUTINE
}
