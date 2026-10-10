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
                            @Size(min = 12, message = "Password require a minimum of 12 characters")
                            @Size(max = 256, message = "Password is too long")
                            String password,
                            /**
                             * The IANA zone the client detected on the device, or null from a
                             * client that predates this field.
                             *
                             * <p>Deliberately unvalidated by bean validation. An unknown string
                             * is dropped in favour of the default rather than refusing the
                             * registration: this is a convenience field, and a browser
                             * reporting something the JVM's tz database has not heard of must
                             * not cost someone their account. {@code UserService.registerUser}
                             * does the checking.
                             */
                            String timezone,
                            /**
                             * The language the client's screen is showing ({@code en},
                             * {@code pt}, or a regional tag like {@code pt-BR}), or null from
                             * a client that predates this field. Same contract as
                             * {@code timezone}: unvalidated here, normalised by
                             * {@code UserLanguage}, and dropped rather than refused when it
                             * names a language the app does not ship.
                             */
                            String language) {
}
