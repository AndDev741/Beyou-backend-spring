package beyou.beyouapp.backend.user.deletion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The six digits Beyou mailed, typed back by the person deleting the account. */
public record ConfirmAccountDeletionDTO(
    @NotBlank
    @Pattern(regexp = "\\d{6}", message = "must be the six digits from the email")
    String code
) {
}
