package beyou.beyouapp.backend.unit.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import beyou.beyouapp.backend.monitoring.NudgeJobHeartbeat;
import beyou.beyouapp.backend.notification.engagement.EngagementNudgeScheduler;
import beyou.beyouapp.backend.notification.engagement.EngagementNudgeService;
import beyou.beyouapp.backend.user.User;
import beyou.beyouapp.backend.user.UserRepository;

/**
 * When the pass runs, and — the load-bearing one — when it does not.
 *
 * <p>The flag is the mechanism that keeps the sender from getting ahead of the privacy
 * policy. If it did not actually stop the pass, that ordering would be a comment in a
 * config file rather than a property of the deployment.
 */
class EngagementNudgeSchedulerUnitTest {

    private UserRepository userRepository;
    private EngagementNudgeService nudgeService;
    private NudgeJobHeartbeat heartbeat;
    private EngagementNudgeScheduler scheduler;

    private static final String ZONE = "Etc/UTC";

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        nudgeService = mock(EngagementNudgeService.class);
        heartbeat = mock(NudgeJobHeartbeat.class);
        scheduler = new EngagementNudgeScheduler(userRepository, nudgeService, heartbeat);
    }

    /** Package-private test seams, reached reflectively because the test lives elsewhere. */
    private void configure(boolean enabled, int hour, int clockHourUtc) throws Exception {
        invoke("setEnabled", boolean.class, enabled);
        invoke("setSendLocalHour", int.class, hour);
        invoke("setClock", Clock.class,
                Clock.fixed(Instant.parse(String.format("2026-08-24T%02d:30:00Z", clockHourUtc)), ZoneOffset.UTC));
    }

    private void invoke(String name, Class<?> type, Object value) throws Exception {
        Method method = EngagementNudgeScheduler.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(scheduler, value);
    }

    private User accountIn(String timezone) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTimezone(timezone);
        return user;
    }

    /**
     * The single most important assertion in this file. Merging the sender must send
     * nothing: the privacy policy has to describe a new use of somebody's data before it
     * starts, and this flag is what makes that ordering enforceable rather than remembered.
     */
    @Test
    @DisplayName("does nothing at all while disabled, even at the send hour")
    void sendsNothingWhileDisabled() throws Exception {
        configure(false, 19, 19);

        scheduler.sendDueNudges();

        verifyNoInteractions(userRepository, nudgeService, heartbeat);
    }

    @Test
    @DisplayName("sends at the configured local hour")
    void sendsAtTheSendHour() throws Exception {
        configure(true, 19, 19);
        User user = accountIn(ZONE);
        when(userRepository.findDistinctTimezones()).thenReturn(List.of(ZONE));
        when(userRepository.findAllByTimezone(ZONE)).thenReturn(List.of(user));
        when(nudgeService.dailyBudgetRemaining(any())).thenReturn(true);
        when(nudgeService.considerAccount(eq(user), any())).thenReturn(true);

        scheduler.sendDueNudges();

        verify(nudgeService).considerAccount(eq(user), eq(LocalDate.of(2026, 8, 24)));
        verify(heartbeat).signalCycleCompleted();
    }

    @Test
    @DisplayName("stays quiet at every other hour")
    void ignoresOtherHours() throws Exception {
        configure(true, 19, 9);
        when(userRepository.findDistinctTimezones()).thenReturn(List.of(ZONE));

        scheduler.sendDueNudges();

        verify(userRepository, never()).findAllByTimezone(ZONE);
        verify(nudgeService, never()).considerAccount(any(), any());
        verify(heartbeat)
                .signalCycleCompleted();
    }

    /**
     * The cap is global, so it is re-read per account. A count taken once at the top of a
     * pass over a large timezone would let the pass blow straight through it.
     */
    @Test
    @DisplayName("stops the pass when the daily budget runs out")
    void stopsAtTheDailyCap() throws Exception {
        configure(true, 19, 19);
        User first = accountIn(ZONE);
        User second = accountIn(ZONE);
        when(userRepository.findDistinctTimezones()).thenReturn(List.of(ZONE));
        when(userRepository.findAllByTimezone(ZONE)).thenReturn(List.of(first, second));
        when(nudgeService.dailyBudgetRemaining(any())).thenReturn(true, false);
        when(nudgeService.considerAccount(any(), any())).thenReturn(true);

        scheduler.sendDueNudges();

        verify(nudgeService, times(1)).considerAccount(any(), any());
    }

    /**
     * One account's failure must not end the pass for everybody after it in the list — the
     * same posture the snapshot job takes, and the reason the ERROR log is the alert.
     */
    @Test
    @DisplayName("one failing account does not stop the others")
    void survivesOneFailingAccount() throws Exception {
        configure(true, 19, 19);
        User exploding = accountIn(ZONE);
        User fine = accountIn(ZONE);
        when(userRepository.findDistinctTimezones()).thenReturn(List.of(ZONE));
        when(userRepository.findAllByTimezone(ZONE)).thenReturn(List.of(exploding, fine));
        when(nudgeService.dailyBudgetRemaining(any())).thenReturn(true);
        when(nudgeService.considerAccount(eq(exploding), any())).thenThrow(new RuntimeException("boom"));
        when(nudgeService.considerAccount(eq(fine), any())).thenReturn(true);

        scheduler.sendDueNudges();

        verify(nudgeService).considerAccount(eq(fine), any());
        verify(heartbeat).signalCycleCompleted();
    }

    /**
     * An unparseable timezone on one account must not blind the operator to whether the job
     * itself is alive — the same reasoning the snapshot scheduler documents.
     */
    @Test
    @DisplayName("an unusable timezone does not end the cycle")
    void survivesAnUnparseableTimezone() throws Exception {
        configure(true, 19, 19);
        when(userRepository.findDistinctTimezones()).thenReturn(List.of("Not/AZone", ZONE));
        when(userRepository.findAllByTimezone(ZONE)).thenReturn(List.of());

        scheduler.sendDueNudges();

        // The usable zone was still reached, and the cycle still reported itself alive.
        verify(userRepository).findAllByTimezone(ZONE);
        verify(heartbeat).signalCycleCompleted();
    }
}
