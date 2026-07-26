package beyou.beyouapp.backend.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

/**
 * Signals the error/uptime collector that the scheduled snapshot cycle just finished.
 *
 * <p>An HTTP health check can only tell you the process is up. It cannot tell you that
 * {@code RoutineSnapshotScheduler.processSnapshots()} stopped running — a wedged
 * scheduler thread, a paused container, a cron that silently stopped firing all leave
 * {@code /actuator/health} returning 200 while snapshots quietly stop being written.
 * The collector-side monitor for this is therefore an <em>inverted</em> check: it alerts
 * on the ABSENCE of a check-in, not on a failed request. This class produces that
 * check-in.
 *
 * <p><b>Fail-open by construction.</b> Everything here is best effort. A collector that
 * is down, slow, or misconfigured must never become the reason the snapshot job fails —
 * that would make the monitoring a cause of the outage it exists to detect. So: short
 * timeouts, every exception swallowed and logged, and a caller-side guard in the
 * scheduler as well.
 *
 * <p><b>Off by default.</b> With no {@code monitoring.heartbeat.snapshot-url} configured
 * (dev machines, CI, the test and e2e profiles) every call is a no-op and no outbound
 * request is ever made.
 */
@Component
@Slf4j
public class SnapshotJobHeartbeat {

    /**
     * Deliberately short. This request runs on the scheduler thread, so a collector that
     * accepts the connection and then hangs would otherwise stall the next cycle. Five
     * seconds is far more than a local check-in needs and far less than the hourly cadence.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Null when the heartbeat is not configured, or when the configured value is unusable. */
    private final URI endpoint;

    private final RestClient restClient;

    /**
     * Builds its own {@link RestClient} rather than taking the auto-configured
     * {@code RestClient.Builder}: a monitoring ping wants none of the application's
     * interceptors, and it wants timeouts of its own regardless of the global
     * {@code spring.http.client.*} defaults.
     */
    @Autowired
    public SnapshotJobHeartbeat(@Value("${monitoring.heartbeat.snapshot-url:}") String snapshotUrl) {
        this(snapshotUrl, RestClient.builder().requestFactory(requestFactory()).build());
    }

    /**
     * Injection seam for tests, which supply a {@code MockRestServiceServer}-backed
     * client. Not a Spring candidate — {@code @Autowired} above picks the other one.
     */
    public SnapshotJobHeartbeat(String snapshotUrl, RestClient restClient) {
        this.endpoint = parseEndpoint(snapshotUrl);
        this.restClient = restClient;

        if (this.endpoint == null) {
            log.info("Snapshot job heartbeat disabled (no monitoring.heartbeat.snapshot-url configured)");
        } else {
            log.info("Snapshot job heartbeat enabled");
        }
    }

    /**
     * Checks in with the collector. Call this ONLY after a cycle has completed
     * successfully — a signal sent on entry, or from a run that then failed, turns the
     * monitor into a permanent green light and defeats the point of having it.
     *
     * <p>Never throws.
     */
    public void signalCycleCompleted() {
        if (endpoint == null) {
            return;
        }

        try {
            restClient.post().uri(endpoint).retrieve().toBodilessEntity();
            log.debug("Snapshot job heartbeat sent");
        } catch (Exception e) {
            // A missed check-in is not an incident on its own: the monitor's grace window
            // absorbs a single failure and only alerts if check-ins keep not arriving.
            // WARN, not ERROR, so a flaky collector cannot manufacture error-budget noise.
            log.warn("Snapshot job heartbeat could not be delivered: {}", e.toString());
        }
    }

    /**
     * Parses the configured URL once at startup. A malformed value logs and disables the
     * heartbeat rather than failing the boot: refusing to start the whole backend because
     * a monitoring URL has a typo would be the monitoring causing the outage.
     */
    private static URI parseEndpoint(String snapshotUrl) {
        if (snapshotUrl == null || snapshotUrl.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(snapshotUrl.trim());
            String scheme = uri.getScheme();

            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                log.error("Ignoring monitoring.heartbeat.snapshot-url: not an absolute http(s) URL");
                return null;
            }

            return uri;
        } catch (IllegalArgumentException e) {
            log.error("Ignoring monitoring.heartbeat.snapshot-url: not a valid URL ({})", e.getMessage());
            return null;
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
