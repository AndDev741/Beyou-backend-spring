package beyou.beyouapp.backend.domain.checkday;

/**
 * Which kind of entity an {@link EntityCheckDay} row describes.
 *
 * <p>Persisted as a string and mirrored by the {@code entity_check_day_owner_type_check}
 * constraint in {@code V13__check_progress_and_entity_check_day.sql} — adding a
 * value here without adding it there makes every insert of the new kind fail.
 */
public enum CheckDayOwnerType {
    HABIT,
    TASK,
    ROUTINE,
    USER
}
