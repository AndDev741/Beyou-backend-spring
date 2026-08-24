package beyou.beyouapp.backend.monitoring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signals that the engagement-nudge pass finished.
 *
 * <p>Worth having from the first day this job exists, and arguably more than for the
 * snapshot job: a snapshot pass that stops running shows up eventually as missing history,
 * whereas a nudge pass that stops running looks exactly like a quiet week. Nobody
 * complains about mail they did not receive.
 *
 * <p>Off by default, like its sibling: with no {@code monitoring.heartbeat.nudge-url}
 * configured every call is a no-op and no outbound request is ever made.
 */
@Component
public class NudgeJobHeartbeat extends JobHeartbeat {

    @Autowired
    public NudgeJobHeartbeat(@Value("${monitoring.heartbeat.nudge-url:}") String nudgeUrl) {
        this(nudgeUrl, RestClient.builder().requestFactory(requestFactory()).build());
    }

    /**
     * Injection seam for tests, which supply a {@code MockRestServiceServer}-backed client.
     * Not a Spring candidate — {@code @Autowired} above picks the other one.
     */
    public NudgeJobHeartbeat(String nudgeUrl, RestClient restClient) {
        super(nudgeUrl, restClient, "Nudge job", "monitoring.heartbeat.nudge-url");
    }
}
