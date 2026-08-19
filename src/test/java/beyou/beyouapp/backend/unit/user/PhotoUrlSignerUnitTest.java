package beyou.beyouapp.backend.unit.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.user.PhotoUrlSigner;

/**
 * The photo URL is the only authorization {@code GET /user/photo/{id}} gets, so
 * these are the cases that decide who can read a stranger's face.
 */
class PhotoUrlSignerUnitTest {

    private static final String SECRET = "a-token-secret-long-enough-for-hmac";

    private final PhotoUrlSigner signer = new PhotoUrlSigner(SECRET, 720);

    @Test
    void acceptsAUrlItMintedItself() {
        UUID userId = UUID.randomUUID();
        Map<String, String> query = queryOf(signer.signedQuery(userId, 1234L));

        assertTrue(signer.isValid(userId, query.get("exp"), query.get("sig")));
    }

    @Test
    void carriesThePhotoVersionSoImageCachesStillBust() {
        Map<String, String> query = queryOf(signer.signedQuery(UUID.randomUUID(), 99L));

        assertTrue("99".equals(query.get("v")));
    }

    /** The whole point: a signature is bound to one account and does not travel. */
    @Test
    void rejectsASignatureMintedForSomebodyElse() {
        UUID owner = UUID.randomUUID();
        Map<String, String> query = queryOf(signer.signedQuery(owner, 1234L));

        assertFalse(signer.isValid(UUID.randomUUID(), query.get("exp"), query.get("sig")));
    }

    @Test
    void rejectsAnExpiredUrl() {
        UUID userId = UUID.randomUUID();
        PhotoUrlSigner expiring = new PhotoUrlSigner(SECRET, 0);
        Map<String, String> query = queryOf(expiring.signedQuery(userId, 1234L));

        // exp is already in the past at a zero-minute TTL, give or take the second
        // this test takes to run; either way it must not be honoured a minute later.
        assertFalse(expiring.isValid(userId, String.valueOf(Long.parseLong(query.get("exp")) - 60),
                query.get("sig")));
    }

    /** A different deployment's secret must not produce URLs this one honours. */
    @Test
    void rejectsASignatureFromAnotherSecret() {
        UUID userId = UUID.randomUUID();
        PhotoUrlSigner other = new PhotoUrlSigner("a-completely-different-secret", 720);
        Map<String, String> query = queryOf(other.signedQuery(userId, 1234L));

        assertFalse(signer.isValid(userId, query.get("exp"), query.get("sig")));
    }

    @Test
    void rejectsAMissingOrMalformedSignature() {
        UUID userId = UUID.randomUUID();
        Map<String, String> query = queryOf(signer.signedQuery(userId, 1234L));

        assertFalse(signer.isValid(userId, null, null));
        assertFalse(signer.isValid(userId, query.get("exp"), null));
        assertFalse(signer.isValid(userId, null, query.get("sig")));
        assertFalse(signer.isValid(userId, "not-a-number", query.get("sig")));
        assertFalse(signer.isValid(userId, query.get("exp"), "forged"));
        // Truncation must not pass either — a prefix comparison would let it.
        assertFalse(signer.isValid(userId, query.get("exp"), query.get("sig").substring(0, 8)));
    }

    private static Map<String, String> queryOf(String query) {
        Map<String, String> params = new HashMap<>();
        for (String pair : query.substring(1).split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return params;
    }
}
