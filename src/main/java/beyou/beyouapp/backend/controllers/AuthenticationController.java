package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.security.passwordreset.PasswordResetService;
import beyou.beyouapp.backend.user.UserService;
import beyou.beyouapp.backend.user.UserServiceGoogleOAuth;
import beyou.beyouapp.backend.user.federation.OidcAuthService;
import beyou.beyouapp.backend.user.federation.dto.OidcLoginDTO;
import beyou.beyouapp.backend.security.AuthenticatedUser;
import beyou.beyouapp.backend.user.dto.ForgotPasswordRequestDTO;
import beyou.beyouapp.backend.user.dto.GoogleMobileLoginDTO;
import beyou.beyouapp.backend.user.dto.UserLoginDTO;
import beyou.beyouapp.backend.user.dto.UserRegisterDTO;
import beyou.beyouapp.backend.user.dto.ResetPasswordRequestDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthenticationController {

    private final UserService userService;
    private final UserServiceGoogleOAuth userServiceGoogleOAuth;
    private final RefreshTokenService refreshTokenService;
    private final PasswordResetService passwordResetService;
    private final OidcAuthService oidcAuthService;
    private final AuthenticatedUser authenticatedUser;

    @GetMapping("/verify")
    public ResponseEntity<String> verifyAuthentication(){
        return userService.verifyAuthentication();
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> doLogin(HttpServletRequest request, HttpServletResponse response, @RequestBody @Valid UserLoginDTO userLoginDTO){
        return userService.doLogin(request, response, userLoginDTO);
    }

    /**
     * @param skipAutoVerify E2E only. Asks registration NOT to take the
     *        {@code e2e.auto-verify-email} shortcut, so a test can reach the one state
     *        that shortcut hides: an account whose verification mail never arrived.
     *        Ignored unless {@code e2e.expose-verification-token} is on, which
     *        {@code SecurityConfigValidator} refuses to let prod boot with.
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, String>> doRegister(
            @RequestBody @Valid UserRegisterDTO userRegisterDTO,
            @RequestHeader(value = "X-E2E-Skip-Auto-Verify", required = false) boolean skipAutoVerify){
        return userService.registerUser(userRegisterDTO, skipAutoVerify);
    }

    /**
     * {@code timezone} is optional and carries the browser's detected IANA zone, so a
     * Google account is not created on the UTC calendar. A client that does not send it
     * still works; the boot reconcile picks the account up afterwards.
     */
    @GetMapping("/google")
    public ResponseEntity<Map<String, Object>> googleAuth(@RequestParam("code") String code,
                                @RequestParam(value = "timezone", required = false) String timezone,
                                HttpServletResponse response){
        return userServiceGoogleOAuth.googleAuth(code, timezone, response);
    }

    @PostMapping("/google/mobile")
    public ResponseEntity<Map<String, Object>> googleMobileAuth(@RequestBody @Valid GoogleMobileLoginDTO request,
                                HttpServletResponse response){
        return userServiceGoogleOAuth.googleMobileAuth(request.idToken(), request.timezone(), response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshAccess(HttpServletRequest request, HttpServletResponse response){
        return refreshTokenService.refreshAccessToken(request, response)
                .<ResponseEntity<?>>map(rt -> ResponseEntity.ok(Map.of("refreshToken", rt)))
                .orElseGet(() -> ResponseEntity.ok("Access Token refreshed"));
    }

    @PostMapping("/logout")
    public ResponseEntity<String> logout(HttpServletRequest request, HttpServletResponse response){
        refreshTokenService.revokeRefreshToken(request, response);
        return ResponseEntity.ok("Logged out successfully");
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@RequestBody @Valid ForgotPasswordRequestDTO request){
        passwordResetService.requestPasswordReset(request.email());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/reset-password/validate")
    public ResponseEntity<Map<String, Object>> validateResetPasswordToken(@RequestParam("token") String token){
        passwordResetService.validateToken(token);
        return ResponseEntity.ok(Map.of("valid", true));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, Object>> resetPassword(@RequestBody @Valid ResetPasswordRequestDTO request){
        passwordResetService.resetPassword(request.token(), request.password());
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * The federated providers this deployment offers, for the login screen to render.
     *
     * <p>Public because the login screen is. Returns an empty list when none is
     * configured, which is what makes the feature ship dark: the button is absent
     * rather than present-and-broken.
     */
    @GetMapping("/oidc/providers")
    public ResponseEntity<Map<String, Object>> oidcProviders(){
        return ResponseEntity.ok(Map.of("providers", oidcAuthService.enabledProviders()));
    }

    /**
     * Web sign-in with a federated provider.
     *
     * <p>Answers 403 {@code FEDERATED_LINK_REQUIRED} when the identity verified but may
     * not enter on its own — the client renders "sign in the way you already do, then
     * link this from settings". That is a normal outcome, not an error to retry.
     */
    @PostMapping("/oidc/{provider}")
    public ResponseEntity<Map<String, Object>> oidcLogin(@PathVariable String provider,
                                                         @RequestBody @Valid OidcLoginDTO request,
                                                         HttpServletResponse response){
        return oidcAuthService.login(provider, request.idToken(), request.timezone(), false, response);
    }

    /** The same, on the mobile contract: X-Access-Token header and refreshToken in the body. */
    @PostMapping("/oidc/{provider}/mobile")
    public ResponseEntity<Map<String, Object>> oidcLoginMobile(@PathVariable String provider,
                                                               @RequestBody @Valid OidcLoginDTO request,
                                                               HttpServletResponse response){
        return oidcAuthService.login(provider, request.idToken(), request.timezone(), true, response);
    }

    /**
     * Attaches a provider to the account making the request.
     *
     * <p>Authenticated on purpose, and it is the ONLY way an identity reaches an account
     * that already exists. Deliberately not in the permitAll list in SecurityConfig —
     * the session is the proof that the person adding a second door is already inside.
     */
    @PostMapping("/oidc/{provider}/link")
    public ResponseEntity<Map<String, Object>> oidcLink(@PathVariable String provider,
                                                        @RequestBody @Valid OidcLoginDTO request){
        return oidcAuthService.link(provider, request.idToken(), authenticatedUser.getAuthenticatedUser());
    }
}
