package beyou.beyouapp.backend.user.apple;

import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.security.TokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserMapper;
import beyou.beyouapp.backend.user.apple.dto.AppleMobileLoginDTO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Sign in with Apple on the iOS app: {@code POST /auth/apple/mobile}.
 *
 * <p>Same shape as {@code UserServiceGoogleOAuth.googleMobileAuth}, and the same response
 * contract (access token in {@code X-Access-Token}, refresh token in the body, no cookie),
 * so the app needs no new branch after the call:
 *
 * <ol>
 *   <li>verify the identity token and its nonce ({@link AppleIdTokenVerifier});</li>
 *   <li>find, link or create the account ({@link AppleAccountResolver});</li>
 *   <li>when the Sign in with Apple key is configured and this link has no refresh token
 *       yet, exchange the authorization code for one, so deleting the account can revoke
 *       it ({@link AppleTokenClient}). Outside any transaction, and never fatal;</li>
 *   <li>issue the session.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AppleSignInService {

    /** What a person who hid their address and shared no name is called until they edit it. */
    static final String FALLBACK_NAME = "Beyou";

    private final AppleIdTokenVerifier verifier;
    private final AppleAccountResolver resolver;
    private final AppleTokenClient tokenClient;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserMapper userMapper;

    public ResponseEntity<Map<String, Object>> mobileSignIn(AppleMobileLoginDTO request,
                                                            HttpServletResponse response) {
        AppleIdentity identity = verifier.verify(request.identityToken(), request.nonce());

        AppleAccountResolver.Outcome outcome = resolver.resolve(identity,
                displayName(request.givenName(), request.familyName(), identity),
                request.timezone(), request.language());

        if (outcome instanceof AppleAccountResolver.Outcome.EmailNotVerified) {
            // Byte-for-byte what /auth/login and the Google doors answer, so the app's
            // existing EMAIL_NOT_VERIFIED handling covers this door too.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "EMAIL_NOT_VERIFIED"));
        }
        AppleAccountResolver.Outcome.SignedIn signedIn = (AppleAccountResolver.Outcome.SignedIn) outcome;

        keepRefreshTokenForRevocation(signedIn, request.authorizationCode(), identity.audience());

        User user = signedIn.user();
        String jwtToken = tokenService.generateJwtToken(user);
        String refreshToken = refreshTokenService.createRefreshToken(user);
        tokenService.addJwtTokenToResponse(response, jwtToken, refreshToken, true);

        return ResponseEntity.ok().body(Map.of(
                "success", userMapper.toResponseDTO(user),
                "refreshToken", refreshToken));
    }

    private void keepRefreshTokenForRevocation(AppleAccountResolver.Outcome.SignedIn signedIn,
                                               String authorizationCode, String clientId) {
        if (signedIn.hasRefreshToken()) {
            return;
        }
        if (!tokenClient.isConfigured()) {
            log.info("Sign in with Apple key not configured (APPLE_TEAM_ID / APPLE_KEY_ID / APPLE_PRIVATE_KEY): "
                    + "no refresh token kept for account {}, so deleting it cannot revoke the Apple link",
                    signedIn.user().getId());
            return;
        }
        try {
            tokenClient.exchangeAuthorizationCode(authorizationCode, clientId)
                    .ifPresent(token -> resolver.storeRefreshToken(signedIn.identityId(), token, clientId));
        } catch (Exception e) {
            // Bookkeeping for a later revocation must never cost the sign-in itself.
            log.warn("Could not keep the Apple refresh token for account {}: {}",
                    signedIn.user().getId(), e.getMessage());
        }
    }

    /**
     * The name Apple handed the device, which it does only on the very first authorization.
     * Otherwise the address before the @, unless it is a relay address, whose local part is
     * random letters nobody would recognise as their name.
     */
    static String displayName(String givenName, String familyName, AppleIdentity identity) {
        String joined = (trim(givenName) + " " + trim(familyName)).trim();
        if (joined.length() >= 2) {
            return joined;
        }
        if (identity.email() != null && !identity.privateEmail()) {
            String local = identity.email().split("@", 2)[0].trim();
            if (local.length() >= 2) {
                return local;
            }
        }
        return FALLBACK_NAME;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
