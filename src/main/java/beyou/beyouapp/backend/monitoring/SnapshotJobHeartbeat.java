package beyou.beyouapp.backend.monitoring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signals that the scheduled snapshot cycle just finished.
 *
 * <p>The reasoning for the inverted check — why an HTTP health check cannot answer this,
 * and why every failure here is swallowed — now lives on {@link JobHeartbeat}, shared with
 * the engagement-nudge job. This class is the snapshot job's property and label.
 *
 * <p>Off by default: with no {@code monitoring.heartbeat.snapshot-url} configured (dev
 * machines, CI, the test and e2e profiles) every call is a no-op and no outbound request is
 * ever made.
 */
@Component
public class SnapshotJobHeartbeat extends JobHeartbeat {

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
     * Injection seam for tests, which supply a {@code MockRestServiceServer}-backed client.
     * Not a Spring candidate — {@code @Autowired} above picks the other one.
     */
    public SnapshotJobHeartbeat(String snapshotUrl, RestClient restClient) {
        super(snapshotUrl, restClient, "Snapshot job", "monitoring.heartbeat.snapshot-url");
    }
}
