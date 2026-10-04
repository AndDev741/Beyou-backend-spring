package beyou.beyouapp.backend.domain.notebook.card.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A card as the page's flashcard block lists it. */
public record CardDTO(
        UUID id,
        UUID pageId,
        String front,
        String back,
        String sourceLabel,
        LocalDate dueOn,
        int intervalDays,
        int reps,
        Instant createdAt) {
}
