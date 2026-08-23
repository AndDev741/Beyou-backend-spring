package beyou.beyouapp.backend.controllers;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.dto.ResendVerificationRequestDTO;
import beyou.beyouapp.backend.user.verification.EmailVerificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AuthVerificationController {

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;

    @GetMapping("/auth/verify-email")
    public ResponseEntity<Map<String, String>> verifyEmail(@RequestParam String token) {
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new BusinessException(ErrorKey.INVALID_REQUEST, "Invalid verification token"));

        if (user.getVerificationTokenExpiry() == null ||
                user.getVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Verification token has expired");
        }

        user.setEmailVerified(true);
        user.setVerificationToken(null);
        user.setVerificationTokenExpiry(null);
        userRepository.save(user);

        return ResponseEntity.ok(Map.of("success", "Email verified successfully"));
    }

    /**
     * Mails a fresh verification link, so a lost registration mail stops being fatal.
     *
     * <p><b>The response is the same 200 for every outcome</b>: address unknown, address
     * already verified, cooldown still running, mail on its way. Anything that told the
     * two apart would turn a public endpoint into a way to ask which addresses hold an
     * account, which is exactly what the identical login refusals elsewhere are there to
     * prevent. The service refuses in silence for the same reason; see its class comment.
     */
    @PostMapping("/auth/resend-verification")
    public ResponseEntity<Map<String, String>> resendVerification(
            @RequestBody @Valid ResendVerificationRequestDTO request) {
        String issuedToken = emailVerificationService.resendVerification(request.email());
        String body = "If that account exists and is not yet verified, a new verification email is on its way";

        // E2E only, and never reachable in prod — SecurityConfigValidator refuses to
        // boot with the flag on. Without it a test cannot follow the link it just asked
        // for, because nothing in this stack reads a mailbox.
        if (issuedToken != null && emailVerificationService.isTokenExposed()) {
            return ResponseEntity.ok(Map.of("success", body, "verificationToken", issuedToken));
        }
        return ResponseEntity.ok(Map.of("success", body));
    }
}
