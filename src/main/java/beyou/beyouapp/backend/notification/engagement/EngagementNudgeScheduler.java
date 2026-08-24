package beyou.beyouapp.backend.notification.engagement;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import beyou.beyouapp.backend.monitoring.NudgeJobHeartbeat;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sends the engagement nudges, once per day per timezone, at a civil hour where the reader
 * lives.
 *
 * <p>Deliberately the same shape as {@code RoutineSnapshotScheduler}: an hourly cron that
 * reads the distinct timezones and acts on the ones where the local hour matches. That
 * pattern is already in production and already understood, and it means no cron expression
 * has to be invented per user. What it costs is a pass that wakes 24 times a day to do
 * nothing 23 of them, which is cheaper than any of the alternatives.
 *
 * <p><b>Off by default.</b> {@code engagement.enabled} defaults to false, so merging this
 * sends nothing. That is not caution for its own sake: the privacy policy has to describe a
 * new use of somebody's data <em>before</em> it starts, and a flag makes that ordering a
 * property of the deployment rather than something an operator has to remember. Turning it
 * on is a one-line env change, after the policy is live.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EngagementNudgeScheduler {

    private final UserRepository userRepository;
    private final EngagementNudgeService nudgeService;
    private final NudgeJobHeartbeat heartbeat;

    @Value("${engagement.enabled:false}")
    private boolean enabled;

    /**
     * The local hour the mails go out. Early evening: late enough that somebody who was
     * going to do their routine today mostly has, so the mail is not nagging about a day
     * still in progress, and early enough that acting on it does not mean staying up.
     *
     * <p>Must not collide with the snapshot job's hours (0 and the 2-3 grace window) — not
     * because anything breaks, but because the two would contend for the same connection
     * pool at the same moment for no reason.
     */
    @Value("${engagement.send-local-hour:19}")
    private int sendLocalHour;

    /**
     * The clock the hour gate reads. Not a constructor parameter, matching
     * {@code RoutineSnapshotScheduler}: there is no bean for it, and tests replace it the
     * same way they replace that one's.
     */
    private Clock clock = Clock.systemDefaultZone();

    @Scheduled(cron = "0 30 * * * *")
    public void sendDueNudges() {
        if (!enabled) {
            return;
        }

        log.info("Starting engagement nudge cycle");
        int sent = 0;
        int considered = 0;

        for (String timezone : userRepository.findDistinctTimezones()) {
            try {
                ZoneId zone = ZoneId.of(timezone);
                ZonedDateTime nowThere = ZonedDateTime.now(clock.withZone(zone));
                if (nowThere.getHour() != sendLocalHour) {
                    continue;
                }

                LocalDate ownerToday = nowThere.toLocalDate();
                List<User> users = userRepository.findAllByTimezone(timezone);
                log.info("Nudge hour reached for timezone {}, considering {} accounts", timezone, users.size());

                for (User user : users) {
                    // Re-read per account rather than once per pass: the cap is global, and
                    // a pass over a large timezone could otherwise blow through it using a
                    // count taken before any of these mails existed.
                    if (!nudgeService.dailyBudgetRemaining(ownerToday)) {
                        log.warn("Engagement daily cap reached; stopping the pass for {}", timezone);
                        break;
                    }
                    considered++;
                    try {
                        if (nudgeService.considerAccount(user, ownerToday)) {
                            sent++;
                        }
                    } catch (Exception e) {
                        // One account's failure must not end the pass. Same posture as the
                        // snapshot job: the ERROR log is the alert.
                        log.error("Engagement nudge failed for user {}", user.getId(), e);
                    }
                }
            } catch (Exception e) {
                // An unparseable timezone on one account must not blind the operator to
                // whether the job itself is alive.
                log.error("Engagement nudge pass failed for timezone {}", timezone, e);
            }
        }

        log.info("Engagement nudge cycle finished: {} sent, {} considered", sent, considered);

        // Fail-open, after the work: the collector alerts on the ABSENCE of this, so a job
        // that stopped firing is visible. A collector that is down must never be the reason
        // the pass fails.
        heartbeat.signalCycleCompleted();
    }

    /** Test seam, mirroring {@code RoutineSnapshotScheduler}. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** Test seam: the flag is read from configuration in every real deployment. */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setSendLocalHour(int sendLocalHour) {
        this.sendLocalHour = sendLocalHour;
    }
}
