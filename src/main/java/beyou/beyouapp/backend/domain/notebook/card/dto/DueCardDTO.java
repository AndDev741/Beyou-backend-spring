package beyou.beyouapp.backend.domain.notebook.card.dto;

import java.util.Map;
import java.util.UUID;

import beyou.beyouapp.backend.domain.notebook.card.CardRating;

/**
 * A card in the review queue.
 *
 * @param intervals what each button would schedule, in days, for the labels under the buttons.
 *                  AGAIN is 0: the card comes back in this same session.
 */
public record DueCardDTO(
        UUID id,
        UUID pageId,
        String pageTitle,
        UUID topicId,
        String topicTitle,
        String front,
        String back,
        String sourceLabel,
        Map<CardRating, Integer> intervals) {
}
