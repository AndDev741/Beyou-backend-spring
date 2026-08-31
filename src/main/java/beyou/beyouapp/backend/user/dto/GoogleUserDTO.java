package beyou.beyouapp.backend.user.dto;

/**
 * A Google identity, plus the zone the client claims the device is in.
 *
 * <p>{@code timezone} does NOT come from Google. The web callback carries it as a query
 * parameter and the mobile path takes it from the request body, because the verified ID
 * token has no such claim. It is null whenever the client did not send one, and it is only
 * ever applied when the account is being created.
 *
 * <p>{@code subject} is Google's {@code sub} — the stable per-user id that
 * {@code federated_identities} keys on. It arrives as {@code id} from the v2 userinfo
 * endpoint on the web path and as the token's subject on the mobile one, and it is null
 * on any path that predates it. Nothing authenticates on it yet: it exists so
 * {@code FederatedIdentityService.recordSeenIdentity} can write the row that lets this
 * account resolve by {@code (iss, sub)} in future, the same as every other provider.
 *
 * <p>Server-built, never a {@code @RequestBody}, so the convenience constructors below are
 * safe — Jackson never sees this record and cannot pick the wrong one.
 */
public record GoogleUserDTO(String email, String name, String perfilPhoto, String timezone, String subject) {

    public GoogleUserDTO(String email, String name, String perfilPhoto) {
        this(email, name, perfilPhoto, null, null);
    }

    public GoogleUserDTO(String email, String name, String perfilPhoto, String timezone) {
        this(email, name, perfilPhoto, timezone, null);
    }

    public boolean isGoogleAccount() {
        return true;
    }

    /** The same identity with a client-claimed zone attached. */
    public GoogleUserDTO withTimezone(String timezone) {
        return new GoogleUserDTO(email, name, perfilPhoto, timezone, subject);
    }

    /** The same identity with Google's stable subject attached. */
    public GoogleUserDTO withSubject(String subject) {
        return new GoogleUserDTO(email, name, perfilPhoto, timezone, subject);
    }
}
