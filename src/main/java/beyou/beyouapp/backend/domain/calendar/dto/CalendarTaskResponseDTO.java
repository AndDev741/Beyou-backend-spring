package beyou.beyouapp.backend.domain.calendar.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * One calendar task as both clients see it.
 *
 * <p>{@code notifyAt} is the reminder instant, computed on the server by
 * {@code CalendarReminderTime} in the owner's timezone with their default time standing in for
 * a missing {@code time}. Clients schedule from it and never recompute it.
 *
 * <p>No acknowledgement or mail fields: whether a reminder already got a reaction is the
 * server's business, and the upcoming endpoint only ever returns the ones that did not.
 */
public record CalendarTaskResponseDTO(
    UUID id,
    String title,
    String note,
    LocalDate date,
    @JsonFormat(pattern = "HH:mm")
    LocalTime time,
    int advanceMinutes,
    boolean completed,
    Instant completedAt,
    double xpAwarded,
    Instant notifyAt
) {}
