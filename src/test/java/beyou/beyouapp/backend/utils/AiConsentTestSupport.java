package beyou.beyouapp.backend.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import beyou.beyouapp.backend.security.aiconsent.AiConsentPolicy;
import beyou.beyouapp.backend.user.UserRepository;
import beyou.beyouapp.backend.user.enums.AiConsent;

/**
 * For unit tests that drive a provider client by hand: the clients refuse every call unless a
 * registered {@link AiConsentPolicy} finds a yes, so these register one that always does.
 * Call {@link #allowAll()} before and {@code unregister()} on the result after.
 */
public final class AiConsentTestSupport {

    private AiConsentTestSupport() {}

    public static AiConsentPolicy allowAll() {
        UserRepository users = mock(UserRepository.class);
        when(users.findAiConsent(any())).thenReturn(AiConsent.GRANTED);
        AiConsentPolicy policy = new AiConsentPolicy(users);
        policy.register();
        return policy;
    }
}
