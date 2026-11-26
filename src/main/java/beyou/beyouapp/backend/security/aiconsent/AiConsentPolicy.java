package beyou.beyouapp.backend.security.aiconsent;

import java.util.UUID;

import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

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

    /**
     * The instance the provider clients reach through {@link #requireNow}. Static so the clients
     * (the model, embedding and search clients, built by hand in their unit tests) need no new
     * constructor argument; with no Spring context there is nothing to check and the call passes.
     */
    private static volatile AiConsentPolicy current;

    public AiConsentPolicy(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void register() {
        current = this;
    }

    @PreDestroy
    public void unregister() {
        if (current == this) {
            current = null;
        }
    }

    /**
     * Called right before every request that carries a person's data to a provider, retries
     * and the agent's calls after a tool included: reads the answer fresh and refuses with
     * {@code AI_CONSENT_DECLINED} unless it is a yes. One primary-key read per provider call.
     */
    public static void requireNow(UUID userId) {
        AiConsentPolicy policy = current;
        if (policy == null || userId == null) {
            return;
        }
        AiConsent consent = policy.userRepository.findAiConsent(userId);
        // No row: the account is gone, and with it everything a provider could be sent about
        // it. The request that started this work was already checked with the account in hand.
        if (consent != null && !allows(consent)) {
            throw new BusinessException(ErrorKey.AI_CONSENT_DECLINED,
                    "The account has not allowed the AI features");
        }
    }

    /** True for the refusal above, so a retry loop gives up instead of asking again. */
    public static boolean isRefusal(Throwable error) {
        return error instanceof BusinessException business
                && business.getErrorKey() == ErrorKey.AI_CONSENT_DECLINED;
    }

    public static boolean allows(AiConsent consent) {
        return consent == AiConsent.GRANTED;
    }

    /** The answer as it stands in the database now. */
    public boolean allowsNow(UUID userId) {
        return allows(userRepository.findAiConsent(userId));
    }
}
