package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /auth/resend-verification}.
 *
 * <p>Same shape and same limits as {@link ForgotPasswordRequestDTO}: both are public,
 * unauthenticated, address-only requests, and the 256 cap keeps a megabyte of "email"
 * from reaching the validator.
 */
public record ResendVerificationRequestDTO(
        @NotBlank(message = "Email is Required")
        @Email(message = "Email is invalid")
        @Size(max = 256, message = "Email is too long")
        String email
) {}
