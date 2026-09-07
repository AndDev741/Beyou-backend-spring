package beyou.beyouapp.backend.domain.mood.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One day's entry as the clients see it.
 *
 * <p>No user id: every route is scoped to the caller, so the field would only ever hold
 * the value the client already has.
 */
public record MoodEntryResponseDTO(
    UUID id,
    LocalDate date,
    Integer mood,
    String note,
    Instant updatedAt
) {}
