package beyou.beyouapp.backend.user.federation;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The HTTP-shaped half of federated sign-in: verify, ask
 * {@link FederatedIdentityService} what may happen, and turn the answer into the same
 * response contract the Google endpoints already use.
 *
 * <p>No decision about identity is taken here. That is the point of the split — this
 * class knows about cookies and status codes, and the rule that matters lives in one
 * place that knows about neither.
 */
@Service
@RequiredArgsConstructor
public class OidcAuthService {

    private final OidcProviderProperties properties;
    private final OidcIdTokenVerifier verifier;
    private final FederatedIdentityService federatedIdentityService;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;

    /** The providers the login screen may offer. Empty when none is configured. */
    public List<Map<String, String>> enabledProviders() {
        return properties.getProviders().entrySet().stream()
                // A block whose env vars are unset binds to blanks rather than vanishing,
                // so filter here too: an offered button that can only 404 is worse than
                // no button.
                .filter(e -> e.getValue().getIssuer() != null && !e.getValue().getIssuer().isBlank()
                        && e.getValue().getClientId() != null && !e.getValue().getClientId().isBlank())
                .map(e -> Map.of(
                        "slug", e.getKey(),
                        "displayName", e.getValue().getDisplayName() != null
                                ? e.getValue().getDisplayName() : e.getKey()))
                .toList();
    }

    public ResponseEntity<Map<String, Object>> login(String slug, String idToken, String claimedTimezone,
                                                     boolean mobile, HttpServletResponse response) {
        OidcProviderProperties.Provider provider = provider(slug);
        FederatedPrincipal principal = verifier.verify(idToken, provider).withTimezone(claimedTimezone);

        FederationOutcome outcome = federatedIdentityService.resolve(principal, provider);

        return switch (outcome) {
            case FederationOutcome.LoggedIn loggedIn -> issueTokens(loggedIn.user(), mobile, response);
            case FederationOutcome.LinkRequired linkRequired -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error", "FEDERATED_LINK_REQUIRED",
                            "reason", linkRequired.reason().name(),
                            "provider", slug));
        };
    }

    /** Attaches the provider to the account making the request. */
    public ResponseEntity<Map<String, Object>> link(String slug, String idToken, User user) {
        OidcProviderProperties.Provider provider = provider(slug);
        FederatedPrincipal principal = verifier.verify(idToken, provider);
        federatedIdentityService.link(user, principal);
        return ResponseEntity.ok(Map.of("success", userMapper.toResponseDTO(user)));
    }

    private OidcProviderProperties.Provider provider(String slug) {
        OidcProviderProperties.Provider provider = properties.getProviders().get(slug);
        if (provider == null
                || provider.getIssuer() == null || provider.getIssuer().isBlank()
                || provider.getClientId() == null || provider.getClientId().isBlank()) {
            // A provider that is not configured does not exist. Deleting its config block
            // is the off switch, and it needs no code change to use.
            throw new BusinessException(ErrorKey.OIDC_PROVIDER_UNKNOWN, "Unknown identity provider");
        }
        return provider;
    }

    /** Byte-for-byte what the Google endpoints return, so the clients need one branch. */
    private ResponseEntity<Map<String, Object>> issueTokens(User user, boolean mobile,
                                                            HttpServletResponse response) {
        String jwtToken = tokenService.generateJwtToken(user);
        String refreshToken = refreshTokenService.createRefreshToken(user);

        if (mobile) {
            tokenService.addJwtTokenToResponse(response, jwtToken, refreshToken, true);
            return ResponseEntity.ok(Map.of(
                    "success", userMapper.toResponseDTO(user),
                    "refreshToken", refreshToken));
        }

        tokenService.addJwtTokenToResponse(response, jwtToken, refreshToken);
        return ResponseEntity.ok(Map.of("success", userMapper.toResponseDTO(user)));
    }
}
