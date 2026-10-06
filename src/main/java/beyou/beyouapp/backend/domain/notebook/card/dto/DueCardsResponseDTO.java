package beyou.beyouapp.backend.domain.notebook.card.dto;

import java.util.List;

/** Today's queue. {@code total} counts every due card; {@code cards} holds at most 200 of them. */
public record DueCardsResponseDTO(List<DueCardDTO> cards, int total, int streak) {
}
