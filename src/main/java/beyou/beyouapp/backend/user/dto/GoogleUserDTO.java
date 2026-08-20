package beyou.beyouapp.backend.user.dto;

/**
 * A Google identity, plus the zone the client claims the device is in.
 *
 * <p>{@code timezone} does NOT come from Google. The web callback carries it as a query
 * parameter and the mobile path takes it from the request body, because the verified ID
 * token has no such claim. It is null whenever the client did not send one, and it is only
 * ever applied when the account is being created.
 */
public record GoogleUserDTO(String email, String name, String perfilPhoto, String timezone) {

    public GoogleUserDTO(String email, String name, String perfilPhoto) {
        this(email, name, perfilPhoto, null);
    }

    public boolean isGoogleAccount() {
        return true;
    }

    /** The same identity with a client-claimed zone attached. */
    public GoogleUserDTO withTimezone(String timezone) {
        return new GoogleUserDTO(email, name, perfilPhoto, timezone);
    }
}
