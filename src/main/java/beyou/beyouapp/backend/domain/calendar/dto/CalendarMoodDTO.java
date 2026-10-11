package beyou.beyouapp.backend.domain.calendar.dto;

/**
 * The day's mood, without the diary itself. {@code hasNote} is enough to draw the marker; the
 * text stays on the mood page, which is where someone goes to read what they wrote.
 */
public record CalendarMoodDTO(
    int level,
    boolean hasNote
) {}
