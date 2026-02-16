package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequestDTO(
        @NotBlank(message = "Email is Required")
        @Email(message = "Email is invalid")
        @Size(max = 256, message = "Email is too long")
        String email
) {}
