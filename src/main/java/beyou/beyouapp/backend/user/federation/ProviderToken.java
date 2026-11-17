package beyou.beyouapp.backend.user.federation;

/** A provider refresh token and the client id it was issued to, for revoking it. */
public record ProviderToken(String refreshToken, String clientId) {
}
