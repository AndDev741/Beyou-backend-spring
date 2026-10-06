package beyou.beyouapp.backend.domain.notebook.card.dto;

import beyou.beyouapp.backend.domain.notebook.card.CardRating;
import jakarta.validation.constraints.NotNull;

public record ReviewRequestDTO(@NotNull CardRating rating) {
}
