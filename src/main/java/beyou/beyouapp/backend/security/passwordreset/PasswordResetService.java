package beyou.beyouapp.backend.security.passwordreset;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.notification.EmailService;
import beyou.beyouapp.backend.security.RefreshToken.RefreshTokenService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RefreshTokenService refreshTokenService;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final Base64.Encoder base64Encoder = Base64.getUrlEncoder();

    @Value("${password-reset.token-ttl-minutes}")
    private long tokenTtlMinutes;

    @Value("${password-reset.cooldown-minutes}")
    private long cooldownMinutes;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Transactional
    public void requestPasswordReset(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return; // Avoid user enumeration
        }

        User user = userOpt.get();
        if (user.isGoogleAccount()) {
            return; // Avoid user enumeration for Google accounts
        }

        enforceCooldown(user);
        invalidateActiveTokens(user);

        String rawToken = generateOpaqueToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setCreatedAt(Timestamp.from(Instant.now()));
        token.setExpiresAt(Timestamp.from(Instant.now().plus(Duration.ofMinutes(tokenTtlMinutes))));
        token.setTokenHash(passwordEncoder.encode(rawToken));
        passwordResetTokenRepository.save(token);

        String fullToken = token.getId() + "." + rawToken;
        String resetLink = buildResetLink(fullToken);
        Duration ttl = Duration.ofMinutes(tokenTtlMinutes);
        schedulePasswordResetEmail(user, token.getId(), resetLink, ttl);
    }

    public void validateToken(String token) {
        resolveValidToken(token);
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken resetToken = resolveValidToken(token);
        User user = resetToken.getUser();

        if (user.isGoogleAccount()) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_NOT_ALLOWED, "Password reset not available for Google accounts");
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        resetToken.setUsedAt(Timestamp.from(Instant.now()));
        passwordResetTokenRepository.save(resetToken);

        refreshTokenService.revokeAllForUser(user);
    }

    private PasswordResetToken resolveValidToken(String token) {
        TokenParts parts = parseToken(token);
        PasswordResetToken resetToken = passwordResetTokenRepository.findById(parts.tokenId())
            .orElseThrow(() -> new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_INVALID, "Reset token invalid"));

        if (!passwordEncoder.matches(parts.rawToken(), resetToken.getTokenHash())) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_INVALID, "Reset token invalid");
        }

        if (resetToken.getUsedAt() != null) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_USED, "Reset token already used");
        }

        if (resetToken.getExpiresAt().before(Timestamp.from(Instant.now()))) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_EXPIRED, "Reset token expired");
        }

        return resetToken;
    }

    private void enforceCooldown(User user) {
        passwordResetTokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.getId())
            .ifPresent(latest -> {
                Instant lastCreated = latest.getCreatedAt().toInstant();
                Instant nextAllowed = lastCreated.plus(Duration.ofMinutes(cooldownMinutes));
                if (Instant.now().isBefore(nextAllowed)) {
                    throw new BusinessException(ErrorKey.PASSWORD_RESET_TOO_MANY_REQUESTS, "Too many reset requests");
                }
            });
    }

    private void invalidateActiveTokens(User user) {
        Timestamp now = Timestamp.from(Instant.now());
        passwordResetTokenRepository.invalidateActiveTokens(user.getId(), now, now);
    }

    private void schedulePasswordResetEmail(User user, UUID tokenId, String resetLink, Duration ttl) {
        Runnable sendEmail = () -> {
            try {
                emailService.sendPasswordResetEmail(user.getEmail(), resetLink, ttl, user.getLanguageInUse());
            } catch (Exception ex) {
                log.error("Failed to send password reset email for user {}", user.getId(), ex);
                cleanupFailedResetToken(tokenId, user.getId());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendEmail.run();
                }
            });
        } else {
            sendEmail.run();
        }
    }

    private void cleanupFailedResetToken(UUID tokenId, UUID userId) {
        try {
            passwordResetTokenRepository.deleteById(tokenId);
        } catch (Exception ex) {
            log.error("Failed to cleanup password reset token {} for user {}", tokenId, userId, ex);
        }
    }

    private String buildResetLink(String token) {
        String base = frontendUrl == null ? "" : frontendUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        return base + "/reset-password?token=" + encodedToken;
    }

    private static String generateOpaqueToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }

    private TokenParts parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_INVALID, "Reset token invalid");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 2) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_INVALID, "Reset token invalid");
        }
        UUID tokenId;
        try {
            tokenId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_INVALID, "Reset token invalid");
        }
        String rawToken = parts[1];
        if (rawToken.isBlank()) {
            throw new BusinessException(ErrorKey.PASSWORD_RESET_TOKEN_INVALID, "Reset token invalid");
        }
        return new TokenParts(tokenId, rawToken);
    }

    private record TokenParts(UUID tokenId, String rawToken) {}
}
