package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserRegisterDTO(@NotBlank(message = "Name is Required")
                            @Size(min = 2, message = "Name require a minimum of 2 characters")
                            @Size(max = 256, message = "Name is too long")
                            String name,
                            @NotBlank(message = "Email is Required")
                            @Email(message = "Email is invalid")
                            @Size(max = 256, message = "Email is too long")
                            String email,
                            @NotBlank(message = "Password is Required")
                            @Size(min = 6, message = "Password require a minimum of 6 characters")
                            @Size(max = 256, message = "Password is too long")
                            String password,
                            boolean isGoogleAccount) {
}
