package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.user.dto.GoogleUserDTO;

/**
 * Verifies a Google-issued ID token (mobile sign-in) and returns the trusted
 * profile claims. The seam keeps {@code UserServiceGoogleOAuth} testable and lets
 * the e2e profile swap in a deterministic verifier with no real Google call.
 */
public interface GoogleIdTokenVerifierService {

    /**
     * @param idToken the raw Google ID token from the client
     * @return the verified Google profile (email, name, picture)
     * @throws beyou.beyouapp.backend.exceptions.BusinessException if the token is
     *         invalid, expired, has the wrong audience, or the email is unverified
     */
    GoogleUserDTO verify(String idToken);
}
