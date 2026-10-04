package beyou.beyouapp.backend.domain.notebook.card.dto;

import beyou.beyouapp.backend.domain.common.DTO.RefreshUiDTO;

/**
 * The end of a session.
 *
 * @param paidReviews reviews this call paid for (zero once the day's cap is reached)
 * @param refreshUi   present when XP was paid
 */
public record FinishReviewResponseDTO(int paidReviews, double xpEarned, int streak, RefreshUiDTO refreshUi) {
}
