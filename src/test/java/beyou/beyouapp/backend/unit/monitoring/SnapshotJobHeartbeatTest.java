package beyou.beyouapp.backend.unit.monitoring;

import beyou.beyouapp.backend.monitoring.SnapshotJobHeartbeat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SnapshotJobHeartbeatTest {

    private static final String ENDPOINT =
            "http://glitchtip:8000/api/beyou/heartbeat/11111111-2222-3333-4444-555555555555/";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    @DisplayName("checks in with a POST to the configured endpoint")
    void signalCycleCompleted_postsToConfiguredEndpoint() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        heartbeatFor(ENDPOINT).signalCycleCompleted();

        server.verify();
    }

    @Test
    @DisplayName("sends nothing when no heartbeat URL is configured")
    void signalCycleCompleted_noOpWhenUnconfigured() {
        // No expectations registered: any outbound request fails the test.
        heartbeatFor("").signalCycleCompleted();

        server.verify();
    }

    @Test
    @DisplayName("sends nothing when the configured URL is not an absolute http(s) URL")
    void signalCycleCompleted_noOpWhenUrlIsUnusable() {
        // A typo must disable the heartbeat, not crash the boot or the scheduler.
        assertThatCode(() -> heartbeatFor("glitchtip:8000/heartbeat").signalCycleCompleted())
                .doesNotThrowAnyException();

        server.verify();
    }

    @Test
    @DisplayName("swallows a collector-side failure instead of propagating it")
    void signalCycleCompleted_swallowsCollectorFailure() {
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        SnapshotJobHeartbeat heartbeat = heartbeatFor(ENDPOINT);

        assertThatCode(heartbeat::signalCycleCompleted).doesNotThrowAnyException();
        server.verify();
    }

    private SnapshotJobHeartbeat heartbeatFor(String url) {
        return new SnapshotJobHeartbeat(url, builder.build());
    }
}
