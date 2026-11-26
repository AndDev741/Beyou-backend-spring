package beyou.beyouapp.backend.security.aiconsent;

import java.util.UUID;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.enums.AiConsent;

/**
 * The one rule about the AI answer, and the fresh read that background work needs.
 *
 * <p>Only {@link AiConsent#GRANTED} lets data reach the provider. A request is checked by
 * {@link AiConsentInterceptor} with the principal SecurityFilter just loaded; work that runs
 * later (a roadmap draft, the embedding of a source, the briefing's prose) reads the answer
 * again right before each call with {@link #allowsNow}, so a "no" given while it waited holds.
 */
@Component
public class AiConsentPolicy {

    private final UserRepository userRepository;

    public AiConsentPolicy(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public static boolean allows(AiConsent consent) {
        return consent == AiConsent.GRANTED;
    }

    /** The answer as it stands in the database now. */
    public boolean allowsNow(UUID userId) {
        return allows(userRepository.findAiConsent(userId));
    }
}
