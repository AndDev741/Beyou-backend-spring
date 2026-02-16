package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequestDTO(
        @NotBlank(message = "Token is required")
        String token,
        @NotBlank(message = "Password is Required")
        @Size(min = 6, message = "Password require a minimum of 6 characters")
        @Size(max = 256, message = "the password is too long!")
        String password
) {}
