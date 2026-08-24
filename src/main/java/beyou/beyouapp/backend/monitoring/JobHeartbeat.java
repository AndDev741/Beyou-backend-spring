package beyou.beyouapp.backend.monitoring;

import java.net.URI;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The mechanics behind an inverted "is this scheduled job still running" check-in, shared
 * by every job that has one.
 *
 * <p>An HTTP health check can only say the process is up. It cannot say that a scheduled
 * pass stopped running — a wedged thread, a paused container, a cron that silently stopped
 * firing all leave {@code /actuator/health} returning 200 while the work quietly stops. So
 * the collector-side monitor is inverted: it alerts on the ABSENCE of a check-in. This
 * class produces the check-in.
 *
 * <p><b>Fail-open by construction.</b> Everything here is best effort. A collector that is
 * down, slow or misconfigured must never become the reason the job it watches fails — that
 * would make the monitoring the cause of the outage it exists to detect. Hence short
 * timeouts, every exception swallowed, and a malformed URL disabling the heartbeat rather
 * than failing the boot.
 *
 * <p>Extracted when the nudge job needed the same thing as the snapshot job. Both
 * subclasses are a constructor and a name: the only per-job differences are which property
 * holds the URL and what the log lines call the job.
 */
abstract class JobHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(JobHeartbeat.class);

    /**
     * Deliberately short. These requests run on the scheduler thread, so a collector that
     * accepts the connection and then hangs would otherwise stall the next cycle. Five
     * seconds is far more than a local check-in needs and far less than the hourly cadence.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /** Null when the heartbeat is not configured, or when the configured value is unusable. */
    private final URI endpoint;
    private final RestClient restClient;

    /** What the log lines call this job, e.g. "Snapshot job". */
    private final String jobLabel;

    /** The property that holds the URL, named in the log when the value is rejected. */
    private final String propertyName;

    protected JobHeartbeat(String url, RestClient restClient, String jobLabel, String propertyName) {
        this.restClient = restClient;
        this.jobLabel = jobLabel;
        this.propertyName = propertyName;
        this.endpoint = parseEndpoint(url, propertyName);

        if (this.endpoint == null) {
            log.info("{} heartbeat disabled (no {} configured)", jobLabel, propertyName);
        } else {
            log.info("{} heartbeat enabled", jobLabel);
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
            log.debug("{} heartbeat sent", jobLabel);
        } catch (Exception e) {
            // A missed check-in is not an incident on its own: the monitor's grace window
            // absorbs a single failure and only alerts if check-ins keep not arriving.
            // WARN, not ERROR, so a flaky collector cannot manufacture error-budget noise.
            log.warn("{} heartbeat could not be delivered: {}", jobLabel, e.toString());
        }
    }

    /** Whether a usable endpoint was configured. For tests and for subclass logging. */
    protected boolean isConfigured() {
        return endpoint != null;
    }

    /**
     * Parses the configured URL once at startup. A malformed value logs and disables the
     * heartbeat rather than failing the boot: refusing to start the whole backend because
     * a monitoring URL has a typo would be the monitoring causing the outage.
     */
    private static URI parseEndpoint(String url, String propertyName) {
        if (url == null || url.isBlank()) {
            return null;
        }

        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();

            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                log.error("Ignoring {}: not an absolute http(s) URL", propertyName);
                return null;
            }

            return uri;
        } catch (IllegalArgumentException e) {
            log.error("Ignoring {}: not a valid URL ({})", propertyName, e.getMessage());
            return null;
        }
    }

    /** The shared request factory, with this class's timeouts rather than the app's. */
    protected static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
