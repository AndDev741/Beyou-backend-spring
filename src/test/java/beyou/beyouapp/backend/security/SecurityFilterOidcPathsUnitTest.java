package beyou.beyouapp.backend.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * There are TWO lists of public paths in this codebase and nothing checks that they
 * agree. SecurityFilter's own comment says so, and says that a path permitAll'd in
 * SecurityConfig but missing here is not public — the filter answers 401 before
 * authorization is consulted, and the endpoint looks broken rather than protected.
 *
 * <p>That is exactly what happened when the federated endpoints landed: SecurityConfig
 * was updated, this filter was not, and /auth/oidc/providers answered 401 in e2e while
 * the /link test passed for the wrong reason — the filter was refusing everything.
 *
 * <p>So the shapes are pinned here. The important assertion is the negative one: /link
 * must be refused by this filter too, and it must be refused because it is not on the
 * list rather than because the whole prefix is.
 */
class SecurityFilterOidcPathsUnitTest {

    @Test
    @DisplayName("the login shapes and the provider list are public")
    void publicShapes() {
        assertTrue(SecurityFilter.isPublicOidcPath("/auth/oidc/providers"));
        assertTrue(SecurityFilter.isPublicOidcPath("/auth/oidc/omelhorsite"));
        assertTrue(SecurityFilter.isPublicOidcPath("/auth/oidc/omelhorsite/mobile"));
    }

    @Test
    @DisplayName("linking is NOT public: the session is the proof")
    void linkingIsNotPublic() {
        assertFalse(SecurityFilter.isPublicOidcPath("/auth/oidc/omelhorsite/link"),
                "a session is what proves the person adding a second door is already inside");
    }

    @Test
    @DisplayName("anything else under /auth/oidc/ is protected until listed")
    void defaultDeny() {
        // A prefix check would have let all of these through.
        assertFalse(SecurityFilter.isPublicOidcPath("/auth/oidc/omelhorsite/unlink"));
        assertFalse(SecurityFilter.isPublicOidcPath("/auth/oidc/omelhorsite/link/extra"));
        assertFalse(SecurityFilter.isPublicOidcPath("/auth/oidc/omelhorsite/mobile/link"));
        assertFalse(SecurityFilter.isPublicOidcPath("/auth/oidc/"));
        assertFalse(SecurityFilter.isPublicOidcPath("/auth/oidcx/whatever"));
        assertFalse(SecurityFilter.isPublicOidcPath("/auth/google"));
    }
}
