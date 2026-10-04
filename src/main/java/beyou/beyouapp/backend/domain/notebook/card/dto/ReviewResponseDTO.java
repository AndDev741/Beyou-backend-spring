package beyou.beyouapp.backend.domain.notebook.card.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Where an answer put the card.
 *
 * @param dueAgainToday true after AGAIN: the client puts the card back at the end of the session
 */
public record ReviewResponseDTO(UUID cardId, LocalDate dueOn, int intervalDays, boolean dueAgainToday) {
}
