package beyou.beyouapp.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UserLoginDTO(@Size(max = 256, message = "The email is too long!")
                           @NotBlank(message = "Email is Required")
                           String email,
                           @NotBlank(message = "Password is Required")
                           @Size(max = 300, message = "the password is too long!")
                           String password) {

}
