package beyou.beyouapp.backend.domain.notebook.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Flashcards drafted by the AI and saved to the page. {@code text} narrows them to a passage
 * ("Make cards" under a chat answer, "3 cards from this paragraph"); without it the page and its
 * sources are used.
 */
public record AiCardsRequestDTO(@Size(max = 8000) String text, @Min(1) @Max(12) Integer count) {
}
