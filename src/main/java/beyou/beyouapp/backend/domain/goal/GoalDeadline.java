package beyou.beyouapp.backend.domain.goal;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The few columns of a goal the calendar draws on its end date. A projection rather than the
 * entity, because the entity's owner is an EAGER association that would be loaded alongside
 * every goal for a marker that needs a name and an icon.
 */
public interface GoalDeadline {
    UUID getId();

    String getName();

    String getIconId();

    Boolean getComplete();

    LocalDate getEndDate();
}
