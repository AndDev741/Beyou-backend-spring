package beyou.beyouapp.backend.monitoring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Signals that the calendar reminder mail pass finished.
 *
 * <p>Same reasoning as {@link NudgeJobHeartbeat}: a reminder job that stops running looks
 * exactly like a quiet day, and the first anyone hears of it is a missed appointment. The pass
 * runs every five minutes, so the collector's monitor can be tight.
 *
 * <p>Off by default: with no {@code monitoring.heartbeat.reminder-url} configured every call is
 * a no-op and no outbound request is ever made.
 */
@Component
public class ReminderJobHeartbeat extends JobHeartbeat {

    @Autowired
    public ReminderJobHeartbeat(@Value("${monitoring.heartbeat.reminder-url:}") String reminderUrl) {
        this(reminderUrl, RestClient.builder().requestFactory(requestFactory()).build());
    }

    /**
     * Injection seam for tests, which supply a {@code MockRestServiceServer}-backed client.
     * Not a Spring candidate — {@code @Autowired} above picks the other one.
     */
    public ReminderJobHeartbeat(String reminderUrl, RestClient restClient) {
        super(reminderUrl, restClient, "Reminder job", "monitoring.heartbeat.reminder-url");
    }
}
