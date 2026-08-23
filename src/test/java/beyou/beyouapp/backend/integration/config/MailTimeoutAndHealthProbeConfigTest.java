package beyou.beyouapp.backend.integration.config;

import beyou.beyouapp.backend.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps a slow mail server from becoming an outage.
 *
 * <p>JavaMail defaults every SMTP timeout to {@code -1} — wait forever. That default is
 * load-bearing here for two separate reasons, and this test pins both.
 *
 * <p>First, {@code PasswordResetService.schedulePasswordResetEmail} and
 * {@code AccountDeletionService.sendAfterCommit} send synchronously from
 * {@code afterCommit}, which runs before the JDBC connection returns to the pool. With no
 * timeout, one hung SMTP session holds a user's request and one of Hikari's ten default
 * connections for as long as the provider feels like — so a handful of forgot-password
 * calls can starve the pool and take down endpoints that have nothing to do with mail.
 *
 * <p>Second, Boot's MailHealthIndicator opened a real authenticated SMTP session on every
 * hit of {@code /actuator/health}, uncached. The uptime monitor polls that endpoint with a
 * 10s timeout, so the mail provider's latency decided whether the backend looked alive.
 *
 * <p>The timeouts are asserted on the live {@code JavaMailSenderImpl} rather than on
 * {@code MailProperties}, because the sender's properties are what JavaMail actually reads,
 * and because application-test.yml re-declares part of the same {@code spring.mail.properties}
 * map — this proves the base timeouts survive that overlay instead of being replaced by it.
 */
class MailTimeoutAndHealthProbeConfigTest extends AbstractIntegrationTest {

    private static final String EXPECTED_TIMEOUT_MS = "5000";

    @Autowired
    private JavaMailSenderImpl mailSender;

    @Autowired
    private HealthContributorRegistry healthContributorRegistry;

    @Autowired
    private HealthEndpointGroups healthEndpointGroups;

    @Test
    void everySmtpTimeoutIsBoundedInEveryProfile() {
        Properties properties = mailSender.getJavaMailProperties();

        assertEquals(EXPECTED_TIMEOUT_MS, properties.getProperty("mail.smtp.connectiontimeout"),
                "no connection timeout means an unreachable SMTP host hangs the caller forever");
        assertEquals(EXPECTED_TIMEOUT_MS, properties.getProperty("mail.smtp.timeout"),
                "no read timeout means a server that accepts the socket and then goes quiet "
                        + "hangs the caller forever");
        assertEquals(EXPECTED_TIMEOUT_MS, properties.getProperty("mail.smtp.writetimeout"),
                "no write timeout means a stalled send of the message body hangs the caller forever");
    }

    /**
     * The strong form of the assertion above for the probe path: an absent contributor
     * cannot make a network call, whatever a future timeout value happens to be.
     */
    @Test
    void theMailHealthIndicatorIsNotRegisteredAtAll() {
        assertNull(healthContributorRegistry.getContributor("mail"),
                "management.health.mail.enabled must stay false — every failed send already "
                        + "logs at ERROR and every ERROR is already an event in GlitchTip, so the "
                        + "indicator bought nothing and cost a live SMTP handshake per probe");
    }

    /**
     * {@code validate-group-membership} is true by default, so a misspelled contributor name
     * fails the boot — meaning this test class starting at all already proves the three names
     * below resolve. The assertions state the intent: the uptime monitor's group is an
     * allowlist of local checks, so an indicator added later cannot put a network call back
     * on the probe path without someone editing this list on purpose.
     */
    @Test
    void theUptimeGroupContainsOnlyLocalChecks() {
        HealthEndpointGroup uptime = healthEndpointGroups.get("uptime");

        assertNotNull(uptime, "the GlitchTip monitor polls /actuator/health/uptime");
        assertTrue(uptime.isMember("db"), "a backend that cannot reach Postgres is down");
        assertTrue(uptime.isMember("diskSpace"), "a full disk is an outage the monitor should catch");
        assertTrue(uptime.isMember("ping"), "keeps the group non-empty if the others are ever dropped");
        assertFalse(uptime.isMember("mail"),
                "mail is a third party's availability, not ours — a Gmail hiccup must not page anyone");
    }
}
