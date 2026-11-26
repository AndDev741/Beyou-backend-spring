package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.NotNull;

/** {@code PUT /user/ai-consent}: the person's yes or no. There is no way back to "not asked". */
public record AiConsentRequestDTO(@NotNull Boolean granted) {
}
