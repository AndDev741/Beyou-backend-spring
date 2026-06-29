package beyou.beyouapp.backend.user;

import beyou.beyouapp.backend.exceptions.BusinessException;
import beyou.beyouapp.backend.exceptions.ErrorKey;
import beyou.beyouapp.backend.user.dto.GoogleUserDTO;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Real verifier backed by Google's {@link GoogleIdTokenVerifier}, which validates
 * the token signature against Google's cached public keys plus the issuer, audience
 * and expiry in one call.
 */
@Slf4j
@Service
public class GoogleIdTokenVerifierServiceImpl implements GoogleIdTokenVerifierService {

    private final GoogleIdTokenVerifier verifier;

    public GoogleIdTokenVerifierServiceImpl(@Value("${google.mobile.audiences}") String audiences) {
        List<String> audienceList = Arrays.stream(audiences.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        // Built once; the verifier caches Google's public keys internally.
        this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(audienceList)
                .build();
    }

    @Override
    public GoogleUserDTO verify(String idTokenString) {
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
        } catch (Exception e) {
            // An exception here (vs verify() returning null for a merely-invalid token)
            // points at a misconfigured audience, clock skew, or a Google outage — surface
            // the cause so it's diagnosable. Message only; never log the token itself.
            log.warn("Google ID token verification errored: {}", e.getMessage());
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Invalid Google ID token");
        }
        if (idToken == null) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Invalid Google ID token");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new BusinessException(ErrorKey.INVALID_REQUEST, "Google account email not verified");
        }

        String email = payload.getEmail();
        String name = (String) payload.get("name");
        String picture = (String) payload.get("picture");
        return new GoogleUserDTO(email, name, picture);
    }
}
