package beyou.beyouapp.backend.domain.calendar.dto;

import java.util.UUID;

/** A goal whose end date is this day. Archived goals never appear. */
public record CalendarGoalDTO(
    UUID goalId,
    String name,
    String iconId,
    boolean complete
) {}
