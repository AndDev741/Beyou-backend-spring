package beyou.beyouapp.backend.unit.config;

import io.sentry.IScopes;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import io.sentry.spring7.SentryRequestResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the "no PII in the collector" requirement holds for real request data instead
 * of assuming {@code sentry.send-default-pii: false} does what the docs say.
 *
 * <p>Drives the SDK's own {@link SentryRequestResolver} — the component that builds the
 * HTTP {@link Request} attached to every event — with a request carrying the headers
 * this app actually sends: the JWT Authorization header, the refresh-token cookie, and
 * the custom access-token header.
 */
class SentryPiiRedactionTest {

    private static SentryRequestResolver resolverWith(boolean sendDefaultPii) {
        SentryOptions options = new SentryOptions();
        options.setSendDefaultPii(sendDefaultPii);

        IScopes scopes = mock(IScopes.class);
        when(scopes.getOptions()).thenReturn(options);

        return new SentryRequestResolver(scopes);
    }

    private static MockHttpServletRequest requestWithCredentials() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/habit");
        request.addHeader("Authorization", "Bearer super-secret-jwt");
        request.addHeader("Cookie", "refreshToken=super-secret-refresh");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("Content-Type", "application/json");
        request.setContent("{\"name\":\"private habit name\"}".getBytes());
        return request;
    }

    @Test
    void doesNotAttachAuthorizationHeaderOrCookies() {
        Request resolved = resolverWith(false).resolveSentryRequest(requestWithCredentials());

        assertFalse(containsHeaderIgnoringCase(resolved, "Authorization"),
                "The JWT Authorization header must never be attached to an event");
        assertFalse(containsHeaderIgnoringCase(resolved, "Cookie"),
                "The refresh-token cookie must never be attached to an event");
        assertFalse(containsHeaderIgnoringCase(resolved, "X-Forwarded-For"),
                "The caller's IP must never be attached to an event");
        assertNull(resolved.getCookies(), "Cookies must not be attached to an event");
    }

    @Test
    void stillAttachesNonSensitiveRequestContext() {
        Request resolved = resolverWith(false).resolveSentryRequest(requestWithCredentials());

        assertTrue(containsHeaderIgnoringCase(resolved, "Content-Type"),
                "Redaction must be targeted — harmless headers still carry debugging value");
        assertTrue(resolved.getUrl().contains("/api/v1/habit"));
    }

    @Test
    void redactionIsDrivenBySendDefaultPiiAndNotAnAccident() {
        // Guard against a false pass: with PII enabled the SDK *does* attach these, so
        // the assertions above are proving the send-default-pii=false setting, not a
        // resolver that never attaches headers at all.
        Request resolved = resolverWith(true).resolveSentryRequest(requestWithCredentials());

        assertTrue(containsHeaderIgnoringCase(resolved, "Authorization"));
    }

    /**
     * The default {@code max-request-body-size} — pinned in application.yaml — is what
     * keeps request bodies (habit/goal/task text) off events. SentryRequestResolver never
     * reads the body; the separate body event processor is gated on this value.
     */
    @Test
    void requestBodyCaptureIsOffByDefault() {
        assertTrue(new SentryOptions().getMaxRequestBodySize() == SentryOptions.RequestSize.NONE);
    }

    private static boolean containsHeaderIgnoringCase(Request request, String headerName) {
        if (request.getHeaders() == null) {
            return false;
        }
        return request.getHeaders().keySet().stream().anyMatch(headerName::equalsIgnoreCase);
    }
}
